package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponseConverter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.FullHttpResponse;
import java.util.List;

public class HttpResponseEncoder extends MessageToMessageEncoder<NettyHttpResponse> {

    private final NettyHttpResponseConverter converter;

    public HttpResponseEncoder(NettyHttpResponseConverter converter) {
        this.converter = converter;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, NettyHttpResponse msg, List<Object> out) throws Exception {
        FullHttpResponse response = converter.convert(msg);
        out.add(response);
    }
}
