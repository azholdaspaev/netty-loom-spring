package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestBodyLimitHandlerTest {

    private static final int MAX_BODY_BYTES = 16;

    private static final int MAX_SWALLOW_BYTES = 32;

    private static final Duration UNREACHED_SWALLOW_TIMEOUT = Duration.ofSeconds(60);

    @Test
    void shouldInviteBodyWhenClientExpectsContinue() {
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
    void shouldRejectUnsupportedExpectationWith417() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(expecting("something-else", 4));

        FullHttpResponse rejection = channel.readOutbound();
        assertEquals(HttpResponseStatus.EXPECTATION_FAILED, rejection.status());
        rejection.release();
        assertNull(channel.readInbound(), "a request whose expectation cannot be met is not served");

        channel.writeInbound(lastContent("body"));

        assertFalse(channel.isOpen(), "the connection ends once the declared body has been drained");
    }

    @Test
    void shouldRejectDeclaredBodyOverLimitWith413BeforeItIsSent() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(expecting("100-continue", MAX_BODY_BYTES + 1));

        FullHttpResponse rejection = channel.readOutbound();
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, rejection.status(),
            "a body known to be too large must be refused before the client sends it");
        rejection.release();
        assertNull(channel.readInbound());
    }

    @Test
    void shouldRejectDeclaredBodyOverLimitEvenWhenClientAsksNothing() {
        EmbeddedChannel channel = newChannel();
        HttpRequest declaredTooLarge = post();
        declaredTooLarge.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, MAX_BODY_BYTES + 1);

        channel.writeInbound(declaredTooLarge);

        FullHttpResponse rejection = channel.readOutbound();
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, rejection.status(),
            "the declared length settles it whether or not the client waits for an invitation");
        rejection.release();
        assertNull(channel.readInbound(),
            "an endpoint that ignores the body could otherwise commit a 200 the running count can no "
                + "longer replace");
    }

    @Test
    void shouldSayNothingToRequestThatExpectsNothing() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(post());

        assertNull(channel.readOutbound(), "an ordinary request needs no interim answer");
        assertNotNull(channel.readInbound());
    }

    @Test
    void shouldRejectBodyThatOutgrowsLimitAsItArrives() {
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
    void shouldReleaseContentItRejects() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(post());
        channel.readInbound();
        HttpContent overflowing = content("x".repeat(MAX_BODY_BYTES + 1));

        assertThrows(TooLongFrameException.class, () -> channel.writeInbound(overflowing));

        assertEquals(0, overflowing.refCnt(), "content past the limit must not be leaked");
    }

    @Test
    void shouldDropWhatFollowsRejectedBodyRatherThanServeIt() {
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
    void shouldAllowBodyThatExactlyReachesLimit() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(post());
        channel.readInbound();

        channel.writeInbound(content("x".repeat(MAX_BODY_BYTES)));

        HttpContent forwarded = channel.readInbound();
        assertEquals(MAX_BODY_BYTES, forwarded.content().readableBytes(), "the limit is inclusive");
        forwarded.release();
    }

    @Test
    void shouldCountEachRequestAgainstLimitOnItsOwn() {
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

    @Test
    void shouldTellClientAboutCloseItsRefusalCarries() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(expecting("something-else", 4));

        FullHttpResponse rejection = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), rejection.headers().get(HttpHeaderNames.CONNECTION),
            "a pooling client reuses a socket it was not told is going, and fails the next request on it");
        rejection.release();
    }

    @Test
    void shouldStillInviteBodyWithoutAskingForCloseItIsNotMaking() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(expecting("100-continue", 4));

        FullHttpResponse invitation = channel.readOutbound();
        assertEquals(HttpResponseStatus.CONTINUE, invitation.status());
        assertNull(invitation.headers().get(HttpHeaderNames.CONNECTION),
            "an interim response that closed the connection would refuse the body it just asked for");
        invitation.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldHoldCloseWhileRefusedBodyArrives() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(declaringLength(MAX_SWALLOW_BYTES));
        releaseOutbound(channel);
        channel.writeInbound(content("x"));

        assertTrue(channel.isOpen(),
            "closing with the body still arriving resets the connection, and the reset discards the 413");
    }

    @Test
    void shouldCloseOnceRefusedBodyEnds() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(declaringLength(MAX_SWALLOW_BYTES));
        releaseOutbound(channel);
        LastHttpContent last = lastContent("x".repeat(MAX_SWALLOW_BYTES));

        channel.writeInbound(last);

        assertFalse(channel.isOpen(), "nothing is left to drain, so the close is orderly");
        assertEquals(0, last.refCnt(), "a drained body belongs to nobody");
        assertNull(channel.readInbound());
    }

    @Test
    void shouldCloseOnceRefusedBodyPassesSwallowLimit() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(declaringLength(Integer.MAX_VALUE));
        releaseOutbound(channel);

        channel.writeInbound(content("x".repeat(MAX_SWALLOW_BYTES)));
        assertTrue(channel.isOpen(), "the swallow limit is inclusive");

        channel.writeInbound(content("y"));
        assertFalse(channel.isOpen(), "a body past the swallow limit is not worth reading to its end");
    }

    @Test
    void shouldDropRequestThatFollowsRefusal() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(declaringLength(MAX_SWALLOW_BYTES));
        releaseOutbound(channel);

        channel.writeInbound(post());

        assertNull(channel.readInbound(), "the refusal said Connection: close, so nothing after it is served");
    }

    @Test
    void shouldCompleteEveryHeldCloseOnceBodyDrains() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(declaringLength(MAX_SWALLOW_BYTES));
        releaseOutbound(channel);

        ChannelFuture second = channel.close();
        assertFalse(second.isDone(), "a second close must wait for the drain like the first");

        channel.writeInbound(lastContent("x"));
        assertTrue(second.isDone(), "a close left pending would leave its caller waiting on a closed channel");
    }

    @Test
    void shouldCompleteHeldCloseWhenClientHangsUp() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(declaringLength(MAX_SWALLOW_BYTES));
        releaseOutbound(channel);
        ChannelFuture held = channel.close();

        channel.unsafe().close(channel.voidPromise());
        channel.runPendingTasks();

        assertTrue(held.isDone(), "a client that hangs up mid-drain ends the drain, not the swallow timeout");
    }

    @Test
    void shouldNotHoldCloseAfterBodyOutgrowsLimit() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(post());
        channel.readInbound();
        assertThrows(TooLongFrameException.class,
            () -> channel.writeInbound(content("x".repeat(MAX_BODY_BYTES + 1))));

        channel.close();

        assertFalse(channel.isOpen(), "only a refusal this handler answered drains before closing");
    }

    private static EmbeddedChannel newChannel() {
        return new EmbeddedChannel(
            new HttpRequestBodyLimitHandler(MAX_BODY_BYTES, MAX_SWALLOW_BYTES, UNREACHED_SWALLOW_TIMEOUT));
    }

    private static HttpRequest declaringLength(int contentLength) {
        HttpRequest request = post();
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        return request;
    }

    private static void releaseOutbound(EmbeddedChannel channel) {
        FullHttpResponse rejection = channel.readOutbound();
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, rejection.status());
        rejection.release();
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
