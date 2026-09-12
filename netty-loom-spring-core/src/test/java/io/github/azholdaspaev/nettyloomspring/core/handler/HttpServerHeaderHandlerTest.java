package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpServerHeaderHandlerTest {

    @Test
    void shouldStampTheConfiguredValueOnAResponse() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpServerHeaderHandler("MyApp"));

        channel.writeOutbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));

        HttpResponse out = channel.readOutbound();
        assertEquals("MyApp", out.headers().get(HttpHeaderNames.SERVER),
            "every response head must carry the configured Server header");
    }

    @Test
    void shouldReplaceAServerHeaderTheApplicationSet() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpServerHeaderHandler("MyApp"));
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.SERVER, "AppChosen");

        channel.writeOutbound(response);

        DefaultFullHttpResponse out = channel.readOutbound();
        assertEquals(List.of("MyApp"), out.headers().getAll(HttpHeaderNames.SERVER),
            "the configured value must override the application's, as Tomcat's does, not be added beside it");
        out.release();
    }

    @Test
    void shouldRejectAValueTheHeaderValidatorWouldRejectAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new HttpServerHeaderHandler("My\r\nApp"),
            "a value the response's header validator rejects must fail when the handler is built, "
                + "not on every response head written through it");
    }

    @Test
    void shouldPassBodyContentThroughUntouched() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpServerHeaderHandler("MyApp"));
        HttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));

        channel.writeOutbound(content);

        assertSame(content, channel.readOutbound(), "a body chunk has no headers and must not be rewritten");
        content.release();
    }
}
