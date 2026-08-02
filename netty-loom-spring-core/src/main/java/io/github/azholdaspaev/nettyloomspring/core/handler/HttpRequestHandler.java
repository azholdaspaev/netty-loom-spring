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
                    request.release();
                    connectionRegistry.dispatchFinished();
                }
            });
        } catch (Throwable cause) {
            // Ahead of fireExceptionCaught, which can throw in its own right once the event loop is
            // gone (issue #109) and would otherwise leave the dispatch counted forever.
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
