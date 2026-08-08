package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.handler.codec.http.HttpObject;

import java.io.IOException;

/**
 * The seam a dispatcher answers through, one part of the response at a time (issue #37).
 *
 * <p>A response is exactly one {@link io.netty.handler.codec.http.HttpResponse} followed by zero or
 * more {@link io.netty.handler.codec.http.HttpContent} chunks and terminated by exactly one
 * {@link io.netty.handler.codec.http.LastHttpContent}. A {@link io.netty.handler.codec.http.FullHttpResponse}
 * is both ends at once, so a buffered answer is a single call.
 *
 * <p>Framing is the writer's business, not the caller's — it belongs to the connection, and getting it
 * wrong corrupts the body ({@code Transfer-Encoding: chunked} reaching an HTTP/1.0 client). Set a
 * {@code Content-Length} to declare a known size; set neither and the writer decides.
 *
 * <p>The writer takes ownership of every part passed to it, on the failing path as much as the
 * succeeding one. It is not thread-safe, and it is valid only for the duration of the
 * {@link HttpRequestDispatcher#handle} call that received it — the request it reads is released once
 * that call returns.
 */
public interface HttpResponseWriter {

    /**
     * Writes one part of the response, blocking while the connection is unwritable.
     *
     * @throws IOException if the client is gone, or has stopped reading for long enough that the
     *                     connection is given up on
     */
    void write(HttpObject part) throws IOException;
}
