package io.github.azholdaspaev.nettyloom.core.handler;

import io.github.azholdaspaev.nettyloom.core.http.NettyHttpRequest;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;

@FunctionalInterface
public interface ExceptionHandler {

    NettyHttpResponse handle(Throwable exception, NettyHttpRequest request);
}
