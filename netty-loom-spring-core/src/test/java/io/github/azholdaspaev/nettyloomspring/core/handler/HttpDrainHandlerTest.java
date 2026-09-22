package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
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

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpDrainHandlerTest {

    @Test
    void shouldNotTreatConnectionWithPartlyReceivedRequestAsIdle() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel channel = register(registry);

        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"));

        registry.beginDrain();
        channel.runPendingTasks();

        assertTrue(channel.isOpen(), "a connection whose request body is still arriving is not idle");
        assertEquals(1, registry.inFlight(channel));
    }

    @Test
    void shouldDropRequestArrivingOnIdleConnectionWhileDraining() {
        HttpConnectionRegistry registry = newRegistry();
        AtomicBoolean closeRequested = new AtomicBoolean();
        /*
         * The close is swallowed because EmbeddedChannel.close() runs pending tasks (EmbeddedChannel.java:628),
         * deregistering at once and stripping the pipeline, so the body would bypass this handler either way.
         * A real channel defers deregistration past the read batch (AbstractChannel.java:667).
         */
        EmbeddedChannel channel = new EmbeddedChannel(
            new ChannelOutboundHandlerAdapter() {
                @Override
                public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                    closeRequested.set(true);
                }
            },
            new HttpServerKeepAliveHandler(),
            new HttpDrainHandler(registry));
        registry.register(channel);

        registry.beginDrain();
        channel.writeInbound(
            new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload"),
            LastHttpContent.EMPTY_LAST_CONTENT);

        assertNull(channel.readInbound(),
            "neither the head nor the body of a refused request may reach the dispatcher");
        assertTrue(closeRequested.get(),
            "an idle connection is closed while draining, whether or not a request beat the close");
        assertEquals(0, registry.inFlight(channel));
    }

    @Test
    void shouldCloseConnectionAfterLastOwedResponseWhileDraining() {
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
    void shouldKeepConnectionOpenWhenNotDraining() {
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

    @Test
    void shouldNotCountResponseForRequestItNeverSawAgainstInFlight() {
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
