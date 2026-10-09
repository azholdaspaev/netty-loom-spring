package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.HttpRequest;

@FunctionalInterface
public interface NettyRequestOriginResolver {

    NettyRequestOriginResolver DIRECT = NettyRequestOrigin::from;

    NettyRequestOrigin resolve(HttpRequest request, HttpConnectionMetadata connection);
}
