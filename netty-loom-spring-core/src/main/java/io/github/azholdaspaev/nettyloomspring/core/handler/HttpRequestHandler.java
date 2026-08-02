package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.util.concurrent.Executor;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final HttpRequestDispatcher requestDispatcher;
    private final Executor dispatchExecutor;
    private final HttpConnectionRegistry connectionRegistry;

    /**
     * @param dispatchExecutor must run every accepted task exactly once. One that silently discards
     *                         a task it accepted — {@code ThreadPoolExecutor} with
     *                         {@code DiscardPolicy}, say — leaks the request and leaves the dispatch
     *                         counted, which holds every later graceful shutdown open for its whole
     *                         grace period. Rejecting by throwing is fine; that path is handled.
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
                    ctx.fireExceptionCaught(cause);
                } finally {
                    // Ahead of release(), which throws if the dispatcher released the request it was
                    // handed: the count is global and reset() does not clear it, so it must not
                    // depend on a call that can fail.
                    connectionRegistry.dispatchFinished();
                    try {
                        request.release();
                    } catch (Throwable ignored) {
                        // Nothing may escape the task. The catch below compensates for a submission
                        // that never ran; on an Executor that runs tasks inline it would otherwise
                        // also see one that did, and count the same dispatch out twice.
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
     * An HTTP/1.0 client closes the connection unless the server spells out {@code Connection: keep-alive}.
     * {@link io.netty.handler.codec.http.HttpServerKeepAliveHandler} only ever writes {@code Connection: close},
     * so the affirmative header has to be added here — but never over a close the dispatcher asked for.
     * The response itself is always HTTP/1.1, hence the explicit version passed to
     * {@link HttpUtil#setKeepAlive(io.netty.handler.codec.http.HttpHeaders, HttpVersion, boolean)}.
     */
    private static void echoHttp10KeepAlive(FullHttpRequest request, FullHttpResponse response) {
        if (HttpVersion.HTTP_1_0.equals(request.protocolVersion())
            && HttpUtil.isKeepAlive(request)
            && HttpUtil.isKeepAlive(response)) {
            HttpUtil.setKeepAlive(response.headers(), HttpVersion.HTTP_1_0, true);
        }
    }
}
