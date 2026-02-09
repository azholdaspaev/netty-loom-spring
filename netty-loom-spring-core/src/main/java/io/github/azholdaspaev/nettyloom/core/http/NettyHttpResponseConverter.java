package io.github.azholdaspaev.nettyloom.core.http;

import io.netty.handler.codec.http.FullHttpResponse;

public interface NettyHttpResponseConverter {

    FullHttpResponse convert(NettyHttpResponse msg);
}
