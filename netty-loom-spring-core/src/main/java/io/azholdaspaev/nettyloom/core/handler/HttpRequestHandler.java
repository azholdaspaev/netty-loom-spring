package io.azholdaspaev.nettyloom.core.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final HttpRequestDispatcher requestDispatcher;

    public HttpRequestHandler(HttpRequestDispatcher requestDispatcher) {
        this.requestDispatcher = requestDispatcher;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        FullHttpResponse response = requestDispatcher.handle(request);
        ctx.writeAndFlush(response);
    }
}
