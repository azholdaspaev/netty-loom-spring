package io.github.azholdaspaev.nettyloom.core.handler;

import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.ExecutorService;

public class RequestDispatcher extends ChannelInboundHandlerAdapter {

    private final RequestHandler requestHandler;
    private final ExceptionHandler exceptionHandler;
    private final ExecutorService executorService;

    public RequestDispatcher(
            RequestHandler requestHandler, ExceptionHandler exceptionHandler, ExecutorService executorService) {
        this.requestHandler = requestHandler;
        this.exceptionHandler = exceptionHandler;
        this.executorService = executorService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof NettyHttpRequest request) {
            executorService.submit(() -> {
                try {
                    NettyHttpResponse response = requestHandler.handle(request);
                    ctx.writeAndFlush(response);
                } catch (Exception exception) {
                    NettyHttpResponse exceptionResponse = exceptionHandler.handle(exception, request);
                    ctx.writeAndFlush(exceptionResponse);
                }
            });
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
