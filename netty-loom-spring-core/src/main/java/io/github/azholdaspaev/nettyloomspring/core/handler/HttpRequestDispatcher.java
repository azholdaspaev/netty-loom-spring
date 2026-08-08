package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.handler.codec.http.FullHttpRequest;

public interface HttpRequestDispatcher {

    /**
     * Answers {@code request} by writing the whole response to {@code writer} before returning. An
     * implementation that returns having written nothing, or having written only part of a response,
     * leaves the exchange unanswered and is treated as a failure.
     */
    void handle(FullHttpRequest request, HttpConnectionMetadata connection, HttpResponseWriter writer)
        throws Exception;
}
