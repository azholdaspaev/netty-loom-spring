package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class HttpRequestHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    private final HttpRequestDispatcher requestDispatcher;
    private final Executor dispatchExecutor;
    private final HttpConnectionRegistry connectionRegistry;
    private final long writeStallTimeoutNanos;

    /**
     * The body of the exchange being dispatched, or null when none is. Event loop only; the dispatch
     * thread reaches it only through the stream's own lock.
     */
    private HttpRequestBodyStream body;

    /** The writer of the exchange being dispatched, read from the event loop to see how far it has got. */
    private HttpChannelResponseWriter writer;

    public HttpRequestHandler(HttpRequestDispatcher requestDispatcher,
                              Executor dispatchExecutor,
                              HttpConnectionRegistry connectionRegistry,
                              Duration writeStallTimeout) {
        this.requestDispatcher = requestDispatcher;
        this.dispatchExecutor = dispatchExecutor;
        this.connectionRegistry = connectionRegistry;
        this.writeStallTimeoutNanos = writeStallTimeout.toNanos();
    }

    /** Nothing is read until it is asked for, so the first request needs an opening ask. */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest request) {
            body = new HttpRequestBodyStream(() -> requestRead(ctx));
            writer = new HttpChannelResponseWriter(ctx, request);
            dispatch(ctx, request, HttpConnectionMetadata.from(ctx), body, writer);
            return;
        }
        if (msg instanceof HttpContent content) {
            if (body == null) {
                // No dispatch owns this: the exchange was answered without reading its body, so the
                // rest of it is drained here rather than left to stall the connection.
                content.release();
                return;
            }
            body.offer(content);
            return;
        }
        ReferenceCountUtil.release(msg);
    }

    /** The valve: more is read only while the consumer is keeping up with what has arrived. */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (body == null || body.hasRoom()) {
            ctx.read();
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        failBody(new ClosedChannelException());
        ctx.fireChannelInactive();
    }

    /**
     * Wakes whoever is reading the body before letting the failure travel on, so a refused upload does
     * not leave the dispatch blocked on a body that will never arrive. Once the response has started,
     * a status written for the failure would encode as more of that body, so the connection is closed
     * instead. Safe to decide here: this runs on the event loop, so a write the dispatch thread issues
     * concurrently is scheduled behind the close.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        boolean midResponse = writer != null && writer.state != ResponseState.NOT_STARTED;
        failBody(cause);
        if (midResponse) {
            ctx.close();
            return;
        }
        ctx.fireExceptionCaught(cause);
    }

    private void failBody(Throwable cause) {
        if (body != null) {
            body.fail(cause);
            body = null;
        }
    }

    private void requestRead(ChannelHandlerContext ctx) {
        ctx.executor().execute(ctx::read);
    }

    private void dispatch(ChannelHandlerContext ctx, HttpRequest request, HttpConnectionMetadata connection,
                          HttpRequestBodyStream requestBody, HttpChannelResponseWriter writer) {
        connectionRegistry.dispatchStarted();
        try {
            dispatchExecutor.execute(() -> {
                try {
                    requestDispatcher.handle(request, requestBody, connection, writer);
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
                        requestBody.close();
                        forget(ctx, requestBody);
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
            requestBody.close();
            body = null;
            ctx.fireExceptionCaught(cause);
        }
    }

    /**
     * By identity, because the gate may already have started the next exchange by the time this runs:
     * clearing unconditionally would orphan its body and hang it.
     */
    private void forget(ChannelHandlerContext ctx, HttpRequestBodyStream finished) {
        ctx.executor().execute(() -> {
            if (body == finished) {
                body = null;
            }
        });
    }

    /**
     * Closes once any response bytes are on the wire, since the tail handler's error would encode as
     * more of the body being read; otherwise hops onto the loop so a terminated one rejects here (#109).
     */
    private static void reportDispatchFailure(ChannelHandlerContext ctx, HttpRequest request,
                                              HttpChannelResponseWriter writer, Throwable cause) {
        if (writer.state != ResponseState.NOT_STARTED) {
            // A client that hung up mid-download ends the stream the ordinary way, so only a fault the
            // server owns is worth a warning. Both are still closes -- there is no response left to
            // send either way -- so the log is the whole of the difference.
            if (cause instanceof ClosedChannelException) {
                log.debug("Client left during {} {}", request.method(), request.uri());
            } else {
                log.warn("Closing {} {} after a failure mid-response: {}",
                    request.method(), request.uri(), cause.toString());
                log.debug("Failure mid-response", cause);
            }
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
    private static void echoHttp10KeepAlive(HttpRequest request, HttpResponse response) {
        if (HttpVersion.HTTP_1_0.equals(request.protocolVersion())
            && HttpUtil.isKeepAlive(request)
            && HttpUtil.isKeepAlive(response)) {
            HttpUtil.setKeepAlive(response.headers(), HttpVersion.HTTP_1_0, true);
        }
    }

    private enum ResponseState { NOT_STARTED, STARTED, ENDED }

    /**
     * Bound to one exchange and touched only on the dispatch thread that owns it. A response written in
     * parts is not atomic on the wire, which widens issue #78.
     */
    private final class HttpChannelResponseWriter implements HttpResponseWriter {

        private final ChannelHandlerContext ctx;
        private final HttpRequest request;

        /**
         * Written by the dispatch thread that owns this, read by the event loop deciding whether a
         * failure can still be answered.
         */
        private volatile ResponseState state = ResponseState.NOT_STARTED;

        HttpChannelResponseWriter(ChannelHandlerContext ctx, HttpRequest request) {
            this.ctx = ctx;
            this.request = request;
        }

        @Override
        public void write(HttpObject part) throws IOException {
            // A handler streaming into a dead channel would otherwise produce for ever, and the part is
            // released here because the writer still owns it. ClosedChannelException, not a plain
            // IOException: the type is what HttpExceptionHandler classifies a departed client by.
            if (!ctx.channel().isActive()) {
                ReferenceCountUtil.release(part);
                throw new ClosedChannelException();
            }
            if (part instanceof HttpResponse response) {
                frameStreamedBody(response);
                echoHttp10KeepAlive(request, response);
            }
            state = part instanceof LastHttpContent ? ResponseState.ENDED : ResponseState.STARTED;
            awaitAccepted(ctx.writeAndFlush(part));
        }

        /**
         * Bounded, because HttpReadTimeoutHandler suspends its clock for the whole exchange; a
         * non-positive bound disables that, as it does the read timeout's.
         */
        private void awaitAccepted(ChannelFuture write) throws IOException {
            if (ctx.channel().isWritable()) {
                return;
            }
            if (writeStallTimeoutNanos <= 0) {
                write.awaitUninterruptibly();
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
            // Netty repairs most of this set and not 304: sanitizeHeadersBeforeEncode skips it while
            // isContentAlwaysEmpty still drops its body and terminator. Declared empty rather than left
            // bare because isSelfDefinedMessageLength covers 1xx and 204 but not 304 or 205, so a bare
            // head has HttpServerKeepAliveHandler close after every conditional GET.
            if (carriesNoBody(response.status())) {
                HttpUtil.setContentLength(response, 0);
                return;
            }
            // An HTTP/1.0 client reads chunk-size lines as body content, so its response is left for
            // the close to delimit; HttpServerKeepAliveHandler then stamps Connection: close itself.
            if (HttpVersion.HTTP_1_0.equals(request.protocolVersion())) {
                return;
            }
            HttpUtil.setTransferEncodingChunked(response, true);
        }

        /** Tomcat suppresses framing for this same set in {@code Http11Processor.prepareResponse}. */
        private static boolean carriesNoBody(HttpResponseStatus status) {
            return status.codeClass() == HttpStatusClass.INFORMATIONAL
                || status.code() == HttpResponseStatus.NO_CONTENT.code()
                || status.code() == HttpResponseStatus.RESET_CONTENT.code()
                || status.code() == HttpResponseStatus.NOT_MODIFIED.code();
        }
    }
}
