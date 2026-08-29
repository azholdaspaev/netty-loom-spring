package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestBodyLimitHandlerTest {

    private static final int MAX_BODY_BYTES = 16;

    @Test
    void shouldInviteTheBodyWhenTheClientExpectsContinue() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(expecting("100-continue", 4));

        FullHttpResponse interim = channel.readOutbound();
        assertNotNull(interim, "a client that waits for an invitation must get one");
        assertEquals(HttpResponseStatus.CONTINUE, interim.status());
        interim.release();

        HttpRequest forwarded = channel.readInbound();
        assertNotNull(forwarded, "the request must still be served");
        assertNull(forwarded.headers().get(HttpHeaderNames.EXPECT),
            "the expectation is answered here, so nothing below may answer it again");
        assertTrue(channel.isOpen());
    }

    @Test
    void shouldRejectAnUnsupportedExpectationWith417() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(expecting("something-else", 4));

        FullHttpResponse rejection = channel.readOutbound();
        assertEquals(HttpResponseStatus.EXPECTATION_FAILED, rejection.status());
        rejection.release();
        assertNull(channel.readInbound(), "a request whose expectation cannot be met is not served");
        assertFalse(channel.isOpen(), "the declared body would arrive with nothing left to read it");
    }

    @Test
    void shouldRejectADeclaredBodyOverTheLimitWith413BeforeItIsSent() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(expecting("100-continue", MAX_BODY_BYTES + 1));

        FullHttpResponse rejection = channel.readOutbound();
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, rejection.status(),
            "a body known to be too large must be refused before the client sends it");
        rejection.release();
        assertNull(channel.readInbound());
    }

    @Test
    void shouldSayNothingToARequestThatExpectsNothing() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(post());

        assertNull(channel.readOutbound(), "an ordinary request needs no interim answer");
        assertNotNull(channel.readInbound());
    }

    @Test
    void shouldRejectABodyThatOutgrowsTheLimitAsItArrives() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(post());
        channel.readInbound();

        channel.writeInbound(content("x".repeat(MAX_BODY_BYTES)));
        channel.readInbound();

        assertThrows(TooLongFrameException.class,
            () -> channel.writeInbound(content("y")),
            "a body with no declared length is bounded only by what has arrived");
    }

    @Test
    void shouldReleaseTheContentItRejects() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(post());
        channel.readInbound();
        HttpContent overflowing = content("x".repeat(MAX_BODY_BYTES + 1));

        assertThrows(TooLongFrameException.class, () -> channel.writeInbound(overflowing));

        assertEquals(0, overflowing.refCnt(), "content past the limit must not be leaked");
    }

    @Test
    void shouldDropWhatFollowsARejectedBodyRatherThanServeIt() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(post());
        channel.readInbound();
        assertThrows(TooLongFrameException.class,
            () -> channel.writeInbound(content("x".repeat(MAX_BODY_BYTES + 1))));

        HttpContent trailing = new DefaultLastHttpContent(Unpooled.copiedBuffer("more", StandardCharsets.UTF_8));
        channel.writeInbound(trailing);

        assertNull(channel.readInbound(), "the rest of a refused body belongs to nobody");
        assertEquals(0, trailing.refCnt());
    }

    @Test
    void shouldAllowABodyThatExactlyReachesTheLimit() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(post());
        channel.readInbound();

        channel.writeInbound(content("x".repeat(MAX_BODY_BYTES)));

        HttpContent forwarded = channel.readInbound();
        assertEquals(MAX_BODY_BYTES, forwarded.content().readableBytes(), "the limit is inclusive");
        forwarded.release();
    }

    @Test
    void shouldCountEachRequestAgainstTheLimitOnItsOwn() {
        EmbeddedChannel channel = newChannel();

        for (int request = 0; request < 3; request++) {
            channel.writeInbound(post());
            channel.readInbound();
            channel.writeInbound(lastContent("x".repeat(MAX_BODY_BYTES)));
            HttpContent body = channel.readInbound();
            assertNotNull(body, "request " + request + " is within the limit on its own");
            body.release();
        }
    }

    private static EmbeddedChannel newChannel() {
        return new EmbeddedChannel(new HttpRequestBodyLimitHandler(MAX_BODY_BYTES));
    }

    private static HttpRequest post() {
        return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
    }

    private static HttpRequest expecting(String expectation, int contentLength) {
        HttpRequest request = post();
        request.headers().set(HttpHeaderNames.EXPECT, expectation);
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        return request;
    }

    private static HttpContent content(String text) {
        return new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
    }

    private static LastHttpContent lastContent(String text) {
        return new DefaultLastHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
    }
}
