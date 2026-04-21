package io.azholdaspaev.nettyloom.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HttpRequestHandlerTest {

    @Test
    void shouldWriteDispatcherResponseToChannel() {
        FullHttpResponse canned = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.EMPTY_BUFFER);
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler(_ -> canned));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));

        FullHttpResponse out = channel.readOutbound();
        assertSame(canned, out, "handler must forward the dispatcher's response unchanged");
        out.release();
        channel.finish();
    }

    @Test
    void shouldPassThroughTheIncomingRequestToDispatcher() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher));
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/submit");

        channel.writeInbound(request);

        assertSame(request, dispatcher.lastRequest,
            "handler must hand the inbound request to the dispatcher without wrapping");

        FullHttpResponse out = channel.readOutbound();
        out.release();
        channel.finish();
    }

    @Test
    void shouldInvokeDispatcherOncePerRequest() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/a"));
        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/b"));

        assertEquals(2, dispatcher.callCount);

        FullHttpResponse first = channel.readOutbound();
        FullHttpResponse second = channel.readOutbound();
        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second, "each dispatch must produce its own response");
        first.release();
        second.release();
        channel.finish();
    }

    @Test
    void shouldPropagateDispatcherExceptionDownPipeline() {
        RuntimeException boom = new RuntimeException("boom");
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler(_ -> { throw boom; }),
            capture);

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));

        assertSame(boom, capture.captured,
            "exception from dispatcher must propagate via exceptionCaught");
        assertNull(channel.readOutbound(),
            "handler must not write a response when the dispatcher fails");
        channel.finish();
    }

    private static final class CapturingDispatcher implements HttpRequestDispatcher {

        FullHttpRequest lastRequest;
        int callCount;

        @Override
        public FullHttpResponse handle(FullHttpRequest request) {
            this.lastRequest = request;
            this.callCount++;
            return new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.EMPTY_BUFFER);
        }
    }

    private static final class ExceptionCapturingHandler extends ChannelInboundHandlerAdapter {

        Throwable captured;

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            this.captured = cause;
        }
    }
}
