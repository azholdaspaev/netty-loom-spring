package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    /** Long enough that a merely slow client is never mistaken for one that has stopped reading. */
    private static final Duration WRITE_STALL_TIMEOUT = Duration.ofSeconds(60);

    private final HttpRequestDispatcher requestDispatcher;
    private final Executor dispatchExecutor;
    private final HttpConnectionRegistry connectionRegistry;
    private final long writeStallTimeoutNanos;

    /**
     * @param dispatchExecutor must run every accepted task exactly once. One that silently discards
     *                         an accepted task leaks the request and leaves the dispatch counted,
     *                         holding every later shutdown open for its whole grace period.
     *                         Rejecting by throwing is fine; that path is handled. It must also not
     *                         run tasks on the event loop: a dispatch waits there for an unwritable
     *                         connection to drain, which on the loop is the deadlock that drains it.
     */
    public HttpRequestHandler(HttpRequestDispatcher requestDispatcher,
                              Executor dispatchExecutor,
                              HttpConnectionRegistry connectionRegistry) {
        this(requestDispatcher, dispatchExecutor, connectionRegistry, WRITE_STALL_TIMEOUT);
    }

    /** Package-private so a test can provoke the stall without waiting out the real bound. */
    HttpRequestHandler(HttpRequestDispatcher requestDispatcher,
                       Executor dispatchExecutor,
                       HttpConnectionRegistry connectionRegistry,
                       Duration writeStallTimeout) {
        this.requestDispatcher = requestDispatcher;
        this.dispatchExecutor = dispatchExecutor;
        this.connectionRegistry = connectionRegistry;
        this.writeStallTimeoutNanos = writeStallTimeout.toNanos();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        request.retain();
        HttpConnectionMetadata connection = HttpConnectionMetadata.from(ctx);
        dispatch(ctx, request, connection);
    }

    private void dispatch(ChannelHandlerContext ctx, FullHttpRequest request, HttpConnectionMetadata connection) {
        connectionRegistry.dispatchStarted();
        try {
            dispatchExecutor.execute(() -> {
                HttpChannelResponseWriter writer = new HttpChannelResponseWriter(ctx, request);
                try {
                    requestDispatcher.handle(request, connection, writer);
                    if (writer.state != ResponseState.ENDED && ctx.channel().isActive()) {
                        // The SPI's return no longer carries the response, so a dispatcher can leave the
                        // exchange hanging by returning. Worth saying only while the connection is still
                        // there: a departed client is the ordinary reason a response stops early.
                        reportDispatchFailure(ctx, request, writer,
                            new IllegalStateException("Dispatcher returned without writing a complete response"));
                    }
                } catch (Throwable cause) {
                    reportDispatchFailure(ctx, request, writer, cause);
                } finally {
                    // Ahead of release(), which throws if the dispatcher released the request it was
                    // handed: the count is global and reset() does not clear it, so it must not
                    // depend on a call that can fail.
                    connectionRegistry.dispatchFinished();
                    try {
                        request.release();
                    } catch (Throwable ignored) {
                        // Nothing may escape the task, fatal errors included. Deliberately not paired
                        // with a rethrowIfFatal: on an Executor that runs tasks inline, rethrowing
                        // would reach the catch below -- which exists for a submission that never ran
                        // -- and count the same dispatch out twice.
                    }
                }
            });
        } catch (Throwable cause) {
            connectionRegistry.dispatchFinished();
            request.release();
            ctx.fireExceptionCaught(cause);
        }
    }

    /**
     * Closes once any response bytes are on the wire, since the tail handler's error would encode as
     * more of the body being read; otherwise hops onto the loop so a terminated one rejects here (#109).
     */
    private static void reportDispatchFailure(ChannelHandlerContext ctx, FullHttpRequest request,
                                              HttpChannelResponseWriter writer, Throwable cause) {
        if (writer.state != ResponseState.NOT_STARTED) {
            log.warn("Closing {} {} after a failure mid-response: {}",
                request.method(), request.uri(), cause.toString());
            log.debug("Failure mid-response", cause);
            ctx.close();
            return;
        }
        try {
            ctx.executor().execute(() -> ctx.fireExceptionCaught(cause));
        } catch (RejectedExecutionException terminated) {
            String dispatch = request.method() + " " + request.uri();
            log.warn("Abandoned {} after the event loop terminated: {}", dispatch, cause.toString());
            log.debug("Abandoned {}", dispatch, cause);
        }
    }

    /**
     * An HTTP/1.0 client closes unless told {@code Connection: keep-alive}, and Netty's
     * {@code HttpServerKeepAliveHandler} only ever writes {@code Connection: close}.
     */
    private static void echoHttp10KeepAlive(FullHttpRequest request, HttpResponse response) {
        if (HttpVersion.HTTP_1_0.equals(request.protocolVersion())
            && HttpUtil.isKeepAlive(request)
            && HttpUtil.isKeepAlive(response)) {
            HttpUtil.setKeepAlive(response.headers(), HttpVersion.HTTP_1_0, true);
        }
    }

    private enum ResponseState { NOT_STARTED, STARTED, ENDED }

    /** Bound to one exchange and touched only on the dispatch thread that owns it. */
    private final class HttpChannelResponseWriter implements HttpResponseWriter {

        private final ChannelHandlerContext ctx;
        private final FullHttpRequest request;

        private ResponseState state = ResponseState.NOT_STARTED;

        HttpChannelResponseWriter(ChannelHandlerContext ctx, FullHttpRequest request) {
            this.ctx = ctx;
            this.request = request;
        }

        @Override
        public void write(HttpObject part) throws IOException {
            // A handler streaming into a dead channel would otherwise produce for ever. isActive() is
            // enough on its own, since Netty closes the channel itself on a write error while autoClose
            // is on, and the part is released here because the writer still owns it.
            if (!ctx.channel().isActive()) {
                ReferenceCountUtil.release(part);
                throw new IOException("Connection closed before the response was written");
            }
            if (part instanceof HttpResponse response) {
                frameStreamedBody(response);
                echoHttp10KeepAlive(request, response);
            }
            state = part instanceof LastHttpContent ? ResponseState.ENDED : ResponseState.STARTED;
            awaitAccepted(ctx.writeAndFlush(part));
        }

        /** Bounded, because HttpReadTimeoutHandler suspends its clock for the whole exchange. */
        private void awaitAccepted(ChannelFuture write) throws IOException {
            if (ctx.channel().isWritable()) {
                return;
            }
            if (!write.awaitUninterruptibly(writeStallTimeoutNanos, TimeUnit.NANOSECONDS)) {
                ctx.close();
                throw new IOException("Connection stopped accepting the response");
            }
        }

        /** Settled on the connection, so no dispatcher has to know the version it turns on. */
        private void frameStreamedBody(HttpResponse response) {
            if (response instanceof LastHttpContent
                || HttpUtil.isContentLengthSet(response)
                || HttpUtil.isTransferEncodingChunked(response)) {
                return;
            }
            // An HTTP/1.0 client reads chunk-size lines as body content, so its response is left for
            // the close to delimit; HttpServerKeepAliveHandler then stamps Connection: close itself.
            if (HttpVersion.HTTP_1_0.equals(request.protocolVersion())) {
                return;
            }
            HttpUtil.setTransferEncodingChunked(response, true);
        }
    }
}
