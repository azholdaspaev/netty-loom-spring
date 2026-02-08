package io.github.azholdaspaev.nettyloom.core.handler;

import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;

@FunctionalInterface
public interface RequestHandler {

    NettyHttpResponse handle(NettyHttpRequest request);
}
