package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.handler.codec.http.HttpRequest;

import java.io.InputStream;

public interface HttpRequestDispatcher {

    /**
     * Answers {@code request} by writing the whole response to {@code writer} before returning. An
     * implementation that returns having written nothing, or having written only part of a response,
     * leaves the exchange unanswered and is treated as a failure.
     *
     * <p>{@code body} blocks the calling thread until the next part of the request arrives, and is
     * valid only for the duration of this call. A plain {@link InputStream} rather than the
     * {@code HttpObject} the writer takes: the parts it hands out are reference-counted, and keeping
     * that inside this module is what makes releasing each one exactly once provable here (issue #51).
     */
    void handle(HttpRequest request, InputStream body, HttpConnectionMetadata connection,
                HttpResponseWriter writer) throws Exception;
}
