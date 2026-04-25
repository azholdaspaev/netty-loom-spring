package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.concurrent.Executor;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final HttpRequestDispatcher requestDispatcher;
    private final Executor dispatchExecutor;

    public HttpRequestHandler(HttpRequestDispatcher requestDispatcher, Executor dispatchExecutor) {
        this.requestDispatcher = requestDispatcher;
        this.dispatchExecutor = dispatchExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        request.retain();
        dispatch(ctx, request);
    }

    private void dispatch(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            dispatchExecutor.execute(() -> {
                try {
                    ctx.writeAndFlush(requestDispatcher.handle(request));
                } catch (Throwable cause) {
                    ctx.fireExceptionCaught(cause);
                } finally {
                    request.release();
                }
            });
        } catch (Throwable cause) {
            request.release();
            ctx.fireExceptionCaught(cause);
        }
    }
}
