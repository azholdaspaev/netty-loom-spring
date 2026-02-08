package io.github.azholdaspaev.nettyloom.core.handler;

import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class RequestDispatcher extends ChannelInboundHandlerAdapter {

    private final RequestHandler requestHandler;
    private final ExceptionHandler exceptionHandler;

    public RequestDispatcher(RequestHandler requestHandler, ExceptionHandler exceptionHandler) {
        this.requestHandler = requestHandler;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof NettyHttpRequest request) {
            try {
                NettyHttpResponse response = requestHandler.handle(request);
                ctx.writeAndFlush(response);
            } catch (Exception exception) {
                NettyHttpResponse exceptionResponse = exceptionHandler.handle(exception, request);
                ctx.writeAndFlush(exceptionResponse);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.close();
    }
}
