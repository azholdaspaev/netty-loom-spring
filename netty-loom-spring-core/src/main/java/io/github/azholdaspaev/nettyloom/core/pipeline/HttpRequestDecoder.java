package io.github.azholdaspaev.nettyloom.core.pipeline;

import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequestConverter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.FullHttpRequest;
import java.util.List;

public class HttpRequestDecoder extends MessageToMessageDecoder<FullHttpRequest> {

    private final NettyHttpRequestConverter converter;

    public HttpRequestDecoder(NettyHttpRequestConverter converter) {
        this.converter = converter;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, FullHttpRequest msg, List<Object> out) throws Exception {
        NettyHttpRequest nettyHttpRequest = converter.convert(msg);
        out.add(nettyHttpRequest);
    }
}
