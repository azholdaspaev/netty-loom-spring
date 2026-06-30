package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyHttpServletResponseTest {

    @Test
    void sendErrorDiscardsAnyPreviouslyWrittenBody() throws Exception {
        var response = new NettyHttpServletResponse();
        response.getWriter().write("partial output written before the error");

        response.sendError(HttpResponseStatus.FORBIDDEN.code());

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertEquals(HttpResponseStatus.FORBIDDEN.code(), httpResponse.status().code());
        assertEquals("", httpResponse.content().toString(StandardCharsets.UTF_8));
    }

    @Test
    void sendErrorClearsStaleContentLengthHeader() throws Exception {
        var response = new NettyHttpServletResponse();
        response.setContentLength(100);
        response.getWriter().write("a body that will be discarded by sendError");

        response.sendError(HttpResponseStatus.FORBIDDEN.code());

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        // Empty body must not be advertised with the stale Content-Length: 100, which would
        // make the client hang waiting for bytes that never arrive.
        assertEquals(0, httpResponse.content().readableBytes());
        assertEquals("0", httpResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH));
    }

    @Test
    void resetBufferDiscardsContentBufferedInTheWriter() throws Exception {
        var response = new NettyHttpServletResponse();
        // Writer uses autoFlush=false, so these chars sit in the writer's encoder buffer,
        // not yet flushed to the body — resetBuffer() must discard them, not just body's bytes.
        response.getWriter().write("buffered but not yet flushed to the body");

        response.resetBuffer();

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertEquals(0, httpResponse.content().readableBytes());
    }
}
