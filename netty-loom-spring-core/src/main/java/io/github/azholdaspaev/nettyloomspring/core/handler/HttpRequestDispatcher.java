package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

public interface HttpRequestDispatcher {

    FullHttpResponse handle(FullHttpRequest request) throws Exception;
}
