package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpDrainHandlerTest {

    @Test
    void shouldCloseAConnectionCarryingNoRequest() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel channel = register(registry);

        registry.beginDrain();
        channel.runPendingTasks();

        assertFalse(channel.isOpen(), "an idle keep-alive connection has nothing to drain");
    }

    /**
     * The regression this handler exists for: counting only fully aggregated requests left a
     * connection mid-upload looking idle, so the drain reset it.
     */
    @Test
    void shouldNotTreatAConnectionWithAPartlyReceivedRequestAsIdle() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel channel = register(registry);

        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));

        registry.beginDrain();
        channel.runPendingTasks();

        assertTrue(channel.isOpen(), "a connection whose request body is still arriving is not idle");
        assertEquals(1, registry.inFlight(channel));
    }

    @Test
    void shouldCloseTheConnectionAfterTheLastOwedResponseWhileDraining() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel channel = register(registry);
        receiveRequest(channel);

        registry.beginDrain();
        channel.runPendingTasks();
        assertTrue(channel.isOpen(), "the response is still owed");

        channel.writeOutbound(okResponse());

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "the last response owed must stop the client reusing the connection");
        assertFalse(channel.isOpen());
        out.release();
    }

    /**
     * {@code HttpServerKeepAliveHandler} closes on the first non-keep-alive response it sees, so
     * stamping every owed response would strand the ones still queued behind it.
     */
    @Test
    void shouldDeliverEveryPipelinedResponseBeforeClosing() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel channel = register(registry);
        receiveRequest(channel);
        receiveRequest(channel);

        registry.beginDrain();
        channel.runPendingTasks();

        channel.writeOutbound(okResponse());
        FullHttpResponse first = channel.readOutbound();
        assertFalse(first.headers().contains(HttpHeaderNames.CONNECTION),
            "a response with another still owed behind it must not close the connection");
        assertTrue(channel.isOpen(), "the second pipelined response is still owed");
        first.release();

        channel.writeOutbound(okResponse());
        FullHttpResponse second = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), second.headers().get(HttpHeaderNames.CONNECTION));
        assertFalse(channel.isOpen(), "the connection closes once the last response is written");
        second.release();
    }

    @Test
    void shouldKeepTheConnectionOpenWhenNotDraining() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel channel = register(registry);
        receiveRequest(channel);

        channel.writeOutbound(okResponse());

        FullHttpResponse out = channel.readOutbound();
        assertFalse(out.headers().contains(HttpHeaderNames.CONNECTION),
            "HTTP/1.1 keep-alive is the default and needs no Connection header");
        assertTrue(channel.isOpen());
        assertEquals(0, registry.inFlight(channel));
        out.release();
    }

    /**
     * A malformed request line is rejected by the codec before any {@code HttpRequest} is emitted,
     * yet {@link HttpExceptionHandler} still writes a 400 out past this handler. Counting that
     * response would drive the connection negative and make the next real request look idle.
     */
    @Test
    void shouldNotCountAResponseForARequestItNeverSaw() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel channel = register(registry);

        channel.writeOutbound(okResponse());
        FullHttpResponse rejected = channel.readOutbound();
        rejected.release();

        assertEquals(0, registry.inFlight(channel), "the count must not drift below zero");

        receiveRequest(channel);
        assertEquals(1, registry.inFlight(channel), "the next real request must still register as in flight");
    }

    private static HttpConnectionRegistry newRegistry() {
        return new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
    }

    private static EmbeddedChannel register(HttpConnectionRegistry registry) {
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpServerKeepAliveHandler(),
            new HttpDrainHandler(registry));
        registry.register(channel);
        return channel;
    }

    private static void receiveRequest(EmbeddedChannel channel) {
        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private static FullHttpResponse okResponse() {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        return response;
    }
}
