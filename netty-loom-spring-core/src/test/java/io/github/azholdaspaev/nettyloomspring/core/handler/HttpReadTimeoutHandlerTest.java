package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void shouldNotCloseWhileARequestIsStillBeingServed() {
        // issue #76
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

        // Two steps rather than one bulk elapse: the tick after a response re-arms for the *remainder* of
        // the interval, and a bulk advance cannot tell that from re-arming a whole one — which would
        // overshoot the configured timeout by half on every keep-alive connection, silently.
        elapse(channel, TIMEOUT_MILLIS - 1);
        assertTrue(channel.isOpen(), "the last exchange restarted the clock, so a full interval is owed");
        elapse(channel, 1);
        assertFalse(channel.isOpen(), "it closes once it stops being used");
    }

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

    @Test
    void shouldFireReadTimeoutExceptionSoTheExceptionHandlerCanMapIt() {
        EmbeddedChannel channel = newChannel();

        elapse(channel, TIMEOUT_MILLIS);

        assertThrows(ReadTimeoutException.class, channel::checkException);
    }

    @Test
    void shouldNotCountAResponseForARequestItNeverSaw() {
        EmbeddedChannel channel = newChannel();

        respond(channel);
        receiveRequest(channel);

        elapse(channel, TIMEOUT_MILLIS * 5);
        assertTrue(channel.isOpen(), "the request in flight must still suspend the timeout");
    }

    @Test
    void shouldCloseWhenThePeerNeverAcceptsTheResponseBytes() {
        EmbeddedChannel channel = new EmbeddedChannel(
            new NeverCompletingWrite(),
            new HttpReadTimeoutHandler(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        channel.freezeTime();
        receiveRequest(channel);

        channel.write(okResponse());

        elapse(channel, TIMEOUT_MILLIS);
        assertFalse(channel.isOpen(), "a response the peer never drains must not suspend the timeout for ever");
    }

    @Test
    void shouldNotCountInboundMessagesThatAreNotRequests() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(Unpooled.copiedBuffer(new byte[] {1, 2, 3}));
        ByteBuf passedOn = channel.readInbound();
        passedOn.release();

        elapse(channel, TIMEOUT_MILLIS);
        assertFalse(channel.isOpen(), "a message that is not a request must not suspend the timeout");
    }

    @Test
    void shouldNotEndTheExchangeOnANonFinalPartOfTheResponse() {
        EmbeddedChannel channel = newChannel();
        receiveRequest(channel);

        channel.writeOutbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        HttpResponse head = channel.readOutbound();

        elapse(channel, TIMEOUT_MILLIS * 5);
        assertTrue(channel.isOpen(), "the response head is not the end of the exchange");
        assertInstanceOf(HttpResponse.class, head);

        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);
        elapse(channel, TIMEOUT_MILLIS);
        assertFalse(channel.isOpen(), "the exchange ends with the last of the response");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldDisableItselfWhenTheTimeoutIsNotPositive(long timeoutMillis) {
        EmbeddedChannel channel = newChannel(timeoutMillis);

        elapse(channel, TIMEOUT_MILLIS * 5);

        assertTrue(channel.isOpen(), "a non-positive timeout turns the guard off rather than closing at once");
    }

    @Test
    void shouldPassLifecycleEventsOnDownThePipeline() {
        RecordingEvents downstream = new RecordingEvents();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpReadTimeoutHandler(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), downstream);

        assertTrue(downstream.active, "channelActive must reach handlers below");

        channel.close();
        assertTrue(downstream.inactive, "channelInactive must reach handlers below");
    }

    @Test
    void shouldCloseAPipelinedBurstWhosePeerNeverAcceptsTheResponseBytes() {
        EmbeddedChannel channel = new EmbeddedChannel(
            new NeverCompletingWrite(),
            new HttpReadTimeoutHandler(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            new HttpPipeliningHandler(),
            new RespondToEveryRequest());
        channel.freezeTime();

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/first"));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/second"));

        elapse(channel, TIMEOUT_MILLIS);
        assertFalse(channel.isOpen(), "a latched gate must not make the connection immortal");
    }

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

    /**
     * Moves the clock on and lets any task that has come due run.
     */
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
        channel.writeOutbound(okResponse());
        FullHttpResponse written = channel.readOutbound();
        written.release();
    }

    private static FullHttpResponse okResponse() {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        return response;
    }

    /**
     * Swallows the write and never completes its promise, as a peer with a zero receive window does.
     */
    private static final class NeverCompletingWrite extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * Stands in for the dispatcher, answering on the event loop instead of a virtual thread.
     */
    private static final class RespondToEveryRequest extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            ctx.writeAndFlush(okResponse());
        }
    }

    private static final class RecordingEvents extends ChannelInboundHandlerAdapter {

        private boolean active;
        private boolean inactive;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            active = true;
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            inactive = true;
            ctx.fireChannelInactive();
        }
    }
}
