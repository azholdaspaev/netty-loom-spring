package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
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
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Issue #63 regression gate. HTTP/1.1 identifies a response only by its position, so a connection may
 * only serve one exchange at a time — otherwise pipelined requests are dispatched concurrently and their
 * responses reach the client in completion order.
 *
 * <p>Everything this handler does happens on the event loop, so these tests need no executor and no
 * threads: what reaches the tail inbound, and when, is the whole contract.
 * {@link io.github.azholdaspaev.nettyloomspring.core.server.NettyServerPipeliningTest} covers the
 * resulting byte order on a real socket.
 */
class HttpPipeliningHandlerTest {

    @Test
    void shouldWithholdAPipelinedRequestUntilTheOneBeforeItIsAnswered() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());

        channel.writeInbound(request("/first"), request("/second"));

        assertEquals("/first", uriOf(channel.readInbound()),
            "the first request must be passed on straight away");
        assertNull(channel.readInbound(),
            "a request pipelined behind an unanswered one must not be dispatched alongside it");

        channel.writeOutbound(okResponse());

        assertEquals("/second", uriOf(channel.readInbound()),
            "the withheld request must be dispatched once the exchange before it is answered");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldNotEndTheExchangeOnANonFinalPartOfTheResponse() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());
        channel.writeInbound(request("/first"), request("/second"));
        uriOf(channel.readInbound());

        channel.writeOutbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        assertNull(channel.readInbound(), "the response head does not end the exchange");

        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);
        assertEquals("/second", uriOf(channel.readInbound()),
            "the last of the response ends the exchange");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReleaseARequestStillQueuedWhenTheConnectionDies() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());
        FullHttpRequest serving = request("/serving");
        FullHttpRequest queued = request("/queued");
        channel.writeInbound(serving, queued);

        channel.close();

        assertEquals(0, queued.refCnt(),
            "a request still queued when the connection dies was never passed on, so nothing else frees it");
        assertEquals(1, serving.refCnt(),
            "a request already passed on is owned downstream, not by the queue");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReleaseAQueuedRequestEvenWhenTheResponseIsNeverAcceptedBySocket() {
        // issue #76 review
        EmbeddedChannel channel = new EmbeddedChannel(new NeverCompletingWrite(), new HttpPipeliningHandler());
        channel.writeInbound(request("/first"), request("/second"));
        uriOf(channel.readInbound());

        channel.write(okResponse());
        channel.runPendingTasks();

        assertEquals("/second", uriOf(channel.readInbound()),
            "a queued request must be released once the response is written, not once it is flushed");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldNotRecurseWhenABurstIsAnsweredSynchronously() {
        HoldFirstThenAnswerInLoop responder = new HoldFirstThenAnswerInLoop();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler(), responder);

        // The first request holds the gate; every one after it piles into the queue.
        int queued = 20_000;
        for (int i = 0; i <= queued; i++) {
            channel.writeInbound(request("/" + i));
        }

        // Answering the first releases the whole burst, each answered inside the write that released it.
        channel.write(okResponse());
        channel.runPendingTasks();

        assertEquals(queued + 1, responder.seen,
            "the whole burst must drain iteratively rather than exhaust the stack");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldPassThroughInboundMessagesThatAreNotRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());

        channel.writeInbound("not a request");

        assertEquals("not a request", channel.readInbound(),
            "only requests are gated; anything else must travel on untouched");
        channel.finishAndReleaseAll();
    }

    /**
     * A peer whose receive window never opens: the message is taken, the promise is never completed.
     */
    private static class NeverCompletingWrite extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * Holds the first request without answering it — a dispatch on a virtual thread — then answers every
     * later one in-loop, which is the shape {@link HttpExceptionHandler} produces on a rejected dispatch.
     */
    private static class HoldFirstThenAnswerInLoop extends SimpleChannelInboundHandler<FullHttpRequest> {
        private int seen;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            if (seen++ == 0) {
                return;
            }
            ctx.write(okResponse());
        }
    }

    private static FullHttpRequest request(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    private static FullHttpResponse okResponse() {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        return response;
    }

    private static String uriOf(FullHttpRequest request) {
        String uri = request.uri();
        request.release();
        return uri;
    }
}
