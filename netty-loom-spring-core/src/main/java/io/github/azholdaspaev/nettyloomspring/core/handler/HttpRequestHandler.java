package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    private final HttpRequestDispatcher requestDispatcher;
    private final Executor dispatchExecutor;
    private final HttpConnectionRegistry connectionRegistry;

    /**
     * @param dispatchExecutor must run every accepted task exactly once. One that silently discards
     *                         an accepted task leaks the request and leaves the dispatch counted,
     *                         holding every later shutdown open for its whole grace period.
     *                         Rejecting by throwing is fine; that path is handled.
     */
    public HttpRequestHandler(HttpRequestDispatcher requestDispatcher,
                              Executor dispatchExecutor,
                              HttpConnectionRegistry connectionRegistry) {
        this.requestDispatcher = requestDispatcher;
        this.dispatchExecutor = dispatchExecutor;
        this.connectionRegistry = connectionRegistry;
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
                try {
                    FullHttpResponse response = requestDispatcher.handle(request, connection);
                    echoHttp10KeepAlive(request, response);
                    ctx.writeAndFlush(response);
                } catch (Throwable cause) {
                    reportDispatchFailure(ctx, request, cause);
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
     * Hops onto the loop itself, so a terminated one rejects here rather than inside Netty, which
     * answers its own rejection with two stack traces naming no request (issue #109).
     */
    private static void reportDispatchFailure(ChannelHandlerContext ctx, FullHttpRequest request, Throwable cause) {
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
    private static void echoHttp10KeepAlive(FullHttpRequest request, FullHttpResponse response) {
        if (HttpVersion.HTTP_1_0.equals(request.protocolVersion())
            && HttpUtil.isKeepAlive(request)
            && HttpUtil.isKeepAlive(response)) {
            HttpUtil.setKeepAlive(response.headers(), HttpVersion.HTTP_1_0, true);
        }
    }
}
