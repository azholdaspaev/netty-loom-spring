package io.github.azholdaspaev.nettyloom.core.http;

import io.netty.handler.codec.http.*;

public class DefaultNettyHttpResponseConverter implements NettyHttpResponseConverter {

    @Override
    public FullHttpResponse convert(NettyHttpResponse msg) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        return response;
    }
}
