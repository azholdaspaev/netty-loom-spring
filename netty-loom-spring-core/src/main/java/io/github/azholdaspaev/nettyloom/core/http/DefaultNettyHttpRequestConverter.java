package io.github.azholdaspaev.nettyloom.core.http;

import io.netty.handler.codec.http.FullHttpRequest;

public class DefaultNettyHttpRequestConverter implements NettyHttpRequestConverter {

    @Override
    public NettyHttpRequest convert(FullHttpRequest msg) {
        HttpMethod method = HttpMethod.valueOf(msg.method().name());

        return new DefaultNettyHttpRequest(method, msg.uri());
    }
}
