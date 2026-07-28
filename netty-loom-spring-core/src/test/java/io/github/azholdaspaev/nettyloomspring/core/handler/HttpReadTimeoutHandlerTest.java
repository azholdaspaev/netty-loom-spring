package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock is driven by hand: the handler reads it from {@code ctx.executor().ticker()}, which for an
 * {@link EmbeddedChannel} is a freezable ticker, so every deadline here is exact rather than slept for.
 */
class HttpReadTimeoutHandlerTest {

    private static final long TIMEOUT_MILLIS = 1_000;

    @Test
    void shouldCloseAConnectionThatHasGoneQuietForTheTimeout() {
        EmbeddedChannel channel = newChannel();

        elapse(channel, TIMEOUT_MILLIS);

        assertFalse(channel.isOpen(), "a connection that has sent nothing for the timeout must be closed");
    }

    @Test
    void shouldNotCloseAConnectionThatIsStillWaitingForTheTimeoutToElapse() {
        EmbeddedChannel channel = newChannel();

        elapse(channel, TIMEOUT_MILLIS / 2);

        assertTrue(channel.isOpen(), "the timeout has not elapsed yet");
    }

    /**
     * Issue #76 regression gate. The timeout measures how long we wait on the client, so the time we
     * spend computing an answer must not count against it — a handler slower than the timeout used to
     * have its connection closed mid-request, and the client got a bare close instead of a response.
     */
    @Test
    void shouldNotCloseWhileARequestIsStillBeingServed() {
        EmbeddedChannel channel = newChannel();
        receiveRequest(channel);

        elapse(channel, TIMEOUT_MILLIS * 5);

        assertTrue(channel.isOpen(), "a dispatch in flight is us owing the client, not the client going quiet");
    }

    @Test
    void shouldCloseOneTimeoutAfterTheResponseHasBeenWritten() {
        EmbeddedChannel channel = newChannel();
        receiveRequest(channel);
        elapse(channel, TIMEOUT_MILLIS * 5);

        respond(channel);

        elapse(channel, TIMEOUT_MILLIS - 1);
        assertTrue(channel.isOpen(), "the clock restarts from the response, not from when the request arrived");

        elapse(channel, 1);
        assertFalse(channel.isOpen(), "the connection is idle again once the exchange is over");
    }

    @Test
    void shouldRestartTheClockForEveryExchange() {
        EmbeddedChannel channel = newChannel();

        for (int exchange = 0; exchange < 3; exchange++) {
            elapse(channel, TIMEOUT_MILLIS / 2);
            receiveRequest(channel);
            respond(channel);
            assertTrue(channel.isOpen(), "a connection answering requests is never idle");
        }

        elapse(channel, TIMEOUT_MILLIS);
        assertFalse(channel.isOpen(), "it closes once it stops being used");
    }

    /**
     * The handler sits above the pipelining gate, so a burst arrives as several outstanding requests at
     * once. A boolean would be re-armed by the first response while the rest were still owed.
     */
    @Test
    void shouldStayOpenUntilEveryPipelinedResponseHasBeenWritten() {
        EmbeddedChannel channel = newChannel();
        receiveRequest(channel);
        receiveRequest(channel);

        respond(channel);
        elapse(channel, TIMEOUT_MILLIS * 5);
        assertTrue(channel.isOpen(), "the second pipelined response is still owed");

        respond(channel);
        elapse(channel, TIMEOUT_MILLIS);
        assertFalse(channel.isOpen());
    }

    /**
     * Netty's own exception is reused so {@code HttpExceptionHandler} keeps mapping it to a close with
     * no response written.
     */
    @Test
    void shouldFireReadTimeoutExceptionSoTheExceptionHandlerCanMapIt() {
        EmbeddedChannel channel = newChannel();

        elapse(channel, TIMEOUT_MILLIS);

        assertThrows(ReadTimeoutException.class, channel::checkException);
    }

    /**
     * A response that never saw a request must not drive the count below zero, or the next genuine
     * dispatch would look like an idle connection and be closed out from under itself.
     */
    @Test
    void shouldNotCountAResponseForARequestItNeverSaw() {
        EmbeddedChannel channel = newChannel();

        respond(channel);
        receiveRequest(channel);

        elapse(channel, TIMEOUT_MILLIS * 5);
        assertTrue(channel.isOpen(), "the request in flight must still suspend the timeout");
    }

    /** What a non-positive timeout meant to the stock handler, and has to keep meaning here. */
    @Test
    void shouldDisableItselfWhenTheTimeoutIsNotPositive() {
        EmbeddedChannel channel = newChannel(0);

        elapse(channel, TIMEOUT_MILLIS * 5);

        assertTrue(channel.isOpen(), "a timeout of zero turns the guard off rather than closing at once");
    }

    /**
     * Removal rather than close, because closing an {@link EmbeddedChannel} empties the event loop's
     * scheduled queue by itself and would hide a timer that was never cancelled.
     */
    @Test
    void shouldNotLeaveATimerArmedAfterItIsRemovedFromThePipeline() {
        EmbeddedChannel channel = newChannel();

        channel.pipeline().remove(HttpReadTimeoutHandler.class);

        assertEquals(-1, channel.runScheduledPendingTasks(),
            "a handler no longer in the pipeline must not leave a timer behind to close a live connection");
    }

    private static EmbeddedChannel newChannel() {
        return newChannel(TIMEOUT_MILLIS);
    }

    private static EmbeddedChannel newChannel(long timeoutMillis) {
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpReadTimeoutHandler(timeoutMillis, TimeUnit.MILLISECONDS));
        channel.freezeTime();
        return channel;
    }

    /** Moves the clock on and lets any task that has come due run. */
    private static void elapse(EmbeddedChannel channel, long millis) {
        channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
    }

    private static void receiveRequest(EmbeddedChannel channel) {
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        FullHttpRequest received = channel.readInbound();
        received.release();
    }

    private static void respond(EmbeddedChannel channel) {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        channel.writeOutbound(response);
        FullHttpResponse written = channel.readOutbound();
        written.release();
    }
}
