package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestHandlerTest {

    private static final Executor DIRECT = Runnable::run;

    @Test
    void shouldWriteDispatcherResponseToChannel() {
        FullHttpResponse canned = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.EMPTY_BUFFER);
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _) -> canned, DIRECT));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertSame(canned, out, "handler must forward the dispatcher's response unchanged");
        out.release();
        channel.finish();
    }

    @Test
    void shouldPassThroughTheIncomingRequestToDispatcher() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT));
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/submit");

        channel.writeInbound(request);
        channel.runPendingTasks();

        assertSame(request, dispatcher.lastRequest,
            "handler must hand the inbound request to the dispatcher without wrapping");

        FullHttpResponse out = channel.readOutbound();
        out.release();
        channel.finish();
    }

    @Test
    void passesHttpConnectionMetadataToDispatcher() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        assertEquals(new HttpConnectionMetadata("", 0, "", 0, false), dispatcher.lastConnection,
            "handler must snapshot the connection metadata and pass it to the dispatcher");

        FullHttpResponse out = channel.readOutbound();
        out.release();
        channel.finish();
    }

    @Test
    void shouldInvokeDispatcherOncePerRequest() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT));

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/a"));
        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/b"));
        channel.runPendingTasks();

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
            new HttpRequestHandler((_, _) -> { throw boom; }, DIRECT),
            capture);

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        assertSame(boom, capture.captured,
            "exception from dispatcher must propagate via exceptionCaught");
        assertNull(channel.readOutbound(),
            "handler must not write a response when the dispatcher fails");
        channel.finish();
    }

    @Test
    void shouldReleaseRequestAfterDispatch() {
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestHandler(dispatcher, DIRECT));
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

        channel.writeInbound(request);
        channel.runPendingTasks();

        assertEquals(0, request.refCnt(),
            "handler must balance retain()/release() so the request is freed after dispatch");

        FullHttpResponse out = channel.readOutbound();
        assertNotNull(out);
        out.release();
        channel.finish();
    }

    @Test
    void shouldReleaseRequestAndPropagateWhenExecutorRejects() {
        RejectedExecutionException rejection = new RejectedExecutionException("shutting down");
        Executor rejecting = _ -> { throw rejection; };
        ExceptionCapturingHandler capture = new ExceptionCapturingHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpRequestHandler((_, _) -> { throw new AssertionError("dispatcher must not run"); }, rejecting),
            capture);
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

        channel.writeInbound(request);

        assertEquals(0, request.refCnt(),
            "handler must release the retained request when the executor rejects the task");
        assertSame(rejection, capture.captured,
            "rejection must propagate via exceptionCaught so the pipeline can respond");
        assertNull(channel.readOutbound());
        channel.finish();
    }

    @Test
    void http11WithoutConnectionHeaderKeepsChannelOpen() {
        EmbeddedChannel channel = keepAliveChannel((_, _) -> emptyOkResponse());

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertFalse(out.headers().contains(HttpHeaderNames.CONNECTION),
            "HTTP/1.1 keep-alive is the default and needs no Connection header");
        assertTrue(channel.isOpen(), "HTTP/1.1 connection must be reusable after the response");
        out.release();
        channel.finish();
    }

    @Test
    void http11ConnectionCloseEchoesCloseAndClosesChannel() {
        EmbeddedChannel channel = keepAliveChannel((_, _) -> emptyOkResponse());
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        channel.writeInbound(request);
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "a requested close must be echoed on the response");
        assertFalse(channel.isOpen(), "channel must close after honouring Connection: close");
        out.release();
        channel.finish();
    }

    @Test
    void http10WithoutConnectionHeaderClosesChannel() {
        EmbeddedChannel channel = keepAliveChannel((_, _) -> emptyOkResponse());

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_0, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "HTTP/1.0 defaults to close, which must be spelled out on the response");
        assertFalse(channel.isOpen(), "channel must close after an HTTP/1.0 request without keep-alive");
        out.release();
        channel.finish();
    }

    @Test
    void http10WithKeepAliveEchoesKeepAliveAndKeepsChannelOpen() {
        EmbeddedChannel channel = keepAliveChannel((_, _) -> emptyOkResponse());
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        channel.writeInbound(request);
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.KEEP_ALIVE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "an HTTP/1.0 client only reuses the connection when keep-alive is spelled out");
        assertTrue(channel.isOpen(), "HTTP/1.0 keep-alive connection must stay open");
        out.release();
        channel.finish();
    }

    @Test
    void dispatcherConnectionCloseWinsOverKeepAliveRequest() {
        EmbeddedChannel channel = keepAliveChannel((_, _) -> closingResponse());

        channel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "a Connection: close set by the application must survive");
        assertFalse(channel.isOpen(), "channel must close when the application asks for it");
        out.release();
        channel.finish();
    }

    @Test
    void dispatcherConnectionCloseWinsOverHttp10KeepAliveRequest() {
        EmbeddedChannel channel = keepAliveChannel((_, _) -> closingResponse());
        FullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        channel.writeInbound(request);
        channel.runPendingTasks();

        FullHttpResponse out = channel.readOutbound();
        assertEquals(HttpHeaderValues.CLOSE.toString(), out.headers().get(HttpHeaderNames.CONNECTION),
            "a Connection: close set by the application must not be overwritten by the HTTP/1.0 keep-alive echo");
        assertFalse(channel.isOpen(), "channel must close when the application asks for it");
        out.release();
        channel.finish();
    }

    private static EmbeddedChannel keepAliveChannel(HttpRequestDispatcher dispatcher) {
        return new EmbeddedChannel(
            new HttpServerKeepAliveHandler(),
            new HttpRequestHandler(dispatcher, DIRECT));
    }

    private static FullHttpResponse emptyOkResponse() {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        return response;
    }

    private static FullHttpResponse closingResponse() {
        FullHttpResponse response = emptyOkResponse();
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return response;
    }

    private static final class CapturingDispatcher implements HttpRequestDispatcher {

        FullHttpRequest lastRequest;
        HttpConnectionMetadata lastConnection;
        int callCount;

        @Override
        public FullHttpResponse handle(FullHttpRequest request, HttpConnectionMetadata connection) {
            this.lastRequest = request;
            this.lastConnection = connection;
            this.callCount++;
            return emptyOkResponse();
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
