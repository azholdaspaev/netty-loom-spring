package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
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
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldPassOnTheWithheldRequestOnceTheResponseIsWritten() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());
        channel.writeInbound(request("/first"), request("/second"));
        uriOf(channel.readInbound());

        channel.writeOutbound(okResponse());

        assertEquals("/second", uriOf(channel.readInbound()),
            "the withheld request must be dispatched once the exchange before it is answered");
        channel.finishAndReleaseAll();
    }

    /**
     * The exchange ends with the <em>last</em> of the response, not its head: a response written in
     * parts must not re-open the gate before its body has gone out.
     */
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
    void shouldPassThroughInboundMessagesThatAreNotRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpPipeliningHandler());

        channel.writeInbound("not a request");

        assertEquals("not a request", channel.readInbound(),
            "only requests are gated; anything else must travel on untouched");
        channel.finishAndReleaseAll();
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
