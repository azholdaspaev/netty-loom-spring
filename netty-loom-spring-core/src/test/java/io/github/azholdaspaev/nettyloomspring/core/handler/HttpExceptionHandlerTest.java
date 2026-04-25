package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpExceptionHandlerTest {

    @Test
    void shouldRespondWith500OnRuntimeException() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpExceptionHandler());

        channel.pipeline().fireExceptionCaught(new RuntimeException("boom"));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
        assertEquals("text/plain; charset=utf-8",
            response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Internal Server Error"), "body was: " + body);
        assertEquals(body.getBytes(StandardCharsets.UTF_8).length,
            Integer.parseInt(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)));
        response.release();
        assertFalse(channel.isOpen(), "channel must be closed after error response");
    }

    @Test
    void shouldRespondWith400OnDecoderException() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpExceptionHandler());

        channel.pipeline().fireExceptionCaught(new DecoderException("bad request"));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        response.release();
        assertFalse(channel.isOpen());
    }

    @Test
    void shouldRespondWith413OnTooLongFrameException() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpExceptionHandler());

        channel.pipeline().fireExceptionCaught(new TooLongFrameException("too big"));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, response.status());
        response.release();
        assertFalse(channel.isOpen());
    }

    @Test
    void shouldRespondWith400OnIllegalArgumentException() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpExceptionHandler());

        channel.pipeline().fireExceptionCaught(new IllegalArgumentException("nope"));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        response.release();
        assertFalse(channel.isOpen());
    }

    @Test
    void shouldUnwrapDecoderExceptionCause() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpExceptionHandler());

        channel.pipeline().fireExceptionCaught(
            new DecoderException(new TooLongFrameException("body too big")));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, response.status());
        response.release();
    }

    @Test
    void shouldNotWriteResponseWhenChannelAlreadyClosed() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpExceptionHandler());
        channel.close().syncUninterruptibly();

        channel.pipeline().fireExceptionCaught(new ClosedChannelException());

        Object outbound = channel.readOutbound();
        assertNull(outbound, "must not write on an already-closed channel");
    }
}
