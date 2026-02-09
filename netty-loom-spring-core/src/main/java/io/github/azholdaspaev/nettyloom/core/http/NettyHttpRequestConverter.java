package io.github.azholdaspaev.nettyloom.core.http;

import io.netty.handler.codec.http.FullHttpRequest;

public interface NettyHttpRequestConverter {

    NettyHttpRequest convert(FullHttpRequest msg);
}
