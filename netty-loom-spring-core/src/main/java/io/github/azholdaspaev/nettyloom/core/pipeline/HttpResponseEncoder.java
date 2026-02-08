package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class HttpResponseEncoder extends MessageToByteEncoder<NettyHttpResponse> {

    @Override
    protected void encode(ChannelHandlerContext ctx, NettyHttpResponse msg, ByteBuf out) throws Exception {}
}
