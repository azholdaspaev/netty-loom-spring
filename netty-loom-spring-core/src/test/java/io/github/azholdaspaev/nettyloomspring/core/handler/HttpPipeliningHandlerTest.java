package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.github.azholdaspaev.nettyloomspring.core.support.RecordingReads;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void shouldForwardEveryPartOfTheRequestItIsServing() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());

        channel.writeInbound(head("/upload"), content("body"), lastContent());

        assertEquals("/upload", ((HttpRequest) channel.readInbound()).uri());
        HttpContent body = channel.readInbound();
        assertEquals("body", body.content().toString(StandardCharsets.UTF_8),
            "the body of the request being served must not be held back");
        body.release();
        assertInstanceOf(LastHttpContent.class, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldKeepForwardingTheBodyOfARequestItHasAlreadyAnswered() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());
        channel.writeInbound(head("/upload"));
        assertEquals("/upload", ((HttpRequest) channel.readInbound()).uri());

        channel.writeOutbound(okResponse());
        channel.runPendingTasks();

        channel.writeInbound(content("tail"));
        HttpContent tail = channel.readInbound();
        assertEquals("tail", tail.content().toString(StandardCharsets.UTF_8),
            "the rest of an answered request still belongs to it, not to whatever comes next");
        tail.release();

        channel.writeInbound(lastContent());
        assertInstanceOf(LastHttpContent.class, channel.readInbound());
        channel.runPendingTasks();

        channel.writeInbound(head("/next"));
        assertEquals("/next", ((HttpRequest) channel.readInbound()).uri(),
            "the next request starts once the one before it is off the wire and answered");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldNotLetALaterRequestJumpTheOnesAlreadyWaiting() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());
        channel.writeInbound(request("/first"), request("/second"));
        uriOf(channel.readInbound());

        // Written but not yet drained: the gate is open again while /second is still queued, and one
        // read loop delivers every decoded request before any task submitted from it runs.
        // Driven through the pipeline rather than the channel, whose every operation ends in
        // runPendingTasks() and would close the window before the assertion.
        channel.pipeline().write(okResponse());
        channel.pipeline().fireChannelRead(request("/third"));

        assertNull(channel.readInbound(), "a request may not overtake one already waiting");
        channel.runPendingTasks();
        assertEquals("/second", uriOf(channel.readInbound()), "the queue is served in request order");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldNotStartASecondExchangeWhenTheQueueHasAlreadyMovedOn() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());
        channel.writeInbound(request("/first"));
        assertEquals("/first", uriOf(channel.readInbound()));

        // The release for /first is scheduled here; /second takes the gate before it runs, so that
        // task now refers to an exchange that has already been replaced.
        channel.pipeline().write(okResponse());
        channel.pipeline().fireChannelRead(request("/second"));
        channel.pipeline().fireChannelRead(request("/third"));
        assertEquals("/second", uriOf(channel.readInbound()));

        channel.runPendingTasks();

        assertNull(channel.readInbound(),
            "a stale release must not dispatch a second request alongside the one being served");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldNotEndTheExchangeOnAnInterimResponse() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());
        channel.writeInbound(head("/upload"));
        assertEquals("/upload", ((HttpRequest) channel.readInbound()).uri());

        channel.writeOutbound(continueResponse());
        channel.releaseOutbound();
        channel.writeInbound(lastContent());
        assertInstanceOf(LastHttpContent.class, channel.readInbound());
        channel.runPendingTasks();

        channel.writeInbound(head("/next"));
        assertNull(channel.readInbound(),
            "an invitation to send the body is not the answer that ends the exchange");

        channel.writeOutbound(okResponse());
        channel.runPendingTasks();
        assertEquals("/next", ((HttpRequest) channel.readInbound()).uri());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReleaseQueuedContentWhenTheConnectionDies() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());
        channel.writeInbound(request("/serving"));
        channel.readInbound();
        HttpContent queued = content("never served");
        channel.writeInbound(head("/queued"), queued);

        channel.close();

        assertEquals(0, queued.refCnt(),
            "content queued behind an unanswered request is freed by nothing else");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldWithholdReadsWhileRequestsAreQueued() {
        RecordingReads reads = new RecordingReads();
        EmbeddedChannel channel = new EmbeddedChannel(reads, new HttpPipeliningHandler());
        channel.writeInbound(request("/first"), request("/second"));
        uriOf(channel.readInbound());
        reads.count = 0;

        channel.read();
        assertEquals(0, reads.count, "reading on while a request waits would only grow the queue");

        channel.writeOutbound(okResponse());
        channel.runPendingTasks();
        uriOf(channel.readInbound());

        assertEquals(1, reads.count, "the connection must ask for more once its queue has drained");
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

    private static HttpRequest head(String uri) {
        return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri);
    }

    private static HttpContent content(String text) {
        return new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
    }

    private static LastHttpContent lastContent() {
        return new DefaultLastHttpContent();
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

    private static FullHttpResponse continueResponse() {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        return response;
    }

    private static String uriOf(FullHttpRequest request) {
        String uri = request.uri();
        request.release();
        return uri;
    }
}
