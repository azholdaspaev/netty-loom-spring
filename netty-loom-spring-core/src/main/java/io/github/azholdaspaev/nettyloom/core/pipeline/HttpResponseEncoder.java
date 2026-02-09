package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponseConverter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.http.FullHttpResponse;

public class HttpResponseEncoder extends MessageToByteEncoder<NettyHttpResponse> {

    private final NettyHttpResponseConverter converter;

    public HttpResponseEncoder(NettyHttpResponseConverter converter) {
        this.converter = converter;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, NettyHttpResponse msg, ByteBuf out) throws Exception {
        FullHttpResponse response = converter.convert(msg);
        ctx.writeAndFlush(response);
    }
}
