package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.github.azholdaspaev.nettyloomspring.core.support.RecordingLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.NOPLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.azholdaspaev.nettyloomspring.core.support.RecordingLogger.keyValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpAccessLogHandlerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T10:15:30Z"), ZoneOffset.UTC);
    private static final String CLIENT = "203.0.113.7";

    private final RecordingLogger log = new RecordingLogger();
    private final List<HttpRequest> resolved = new ArrayList<>();

    private HttpAccessLogHandler newHandler() {
        return new HttpAccessLogHandler((request, connection) -> {
            resolved.add(request);
            return CLIENT;
        }, log, CLOCK);
    }

    private EmbeddedChannel newChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(newHandler());
        channel.freezeTime();
        return channel;
    }

    @Test
    void shouldLogServedExchangeAsCombinedLine() {
        EmbeddedChannel channel = newChannel();
        FullHttpRequest request = get("/items?id=1");
        request.headers().set(HttpHeaderNames.REFERER, "https://example.org/");
        request.headers().set(HttpHeaderNames.USER_AGENT, "curl/8.7.1");

        channel.writeInbound(request);
        channel.advanceTimeBy(12, TimeUnit.MILLISECONDS);
        channel.writeOutbound(ok("hello"));

        assertEquals(CLIENT + " - - [25/Oct/2026:10:15:30 +0000] \"GET /items?id=1 HTTP/1.1\" 200 5"
                + " \"https://example.org/\" \"curl/8.7.1\" 12", log.single().getMessage(),
            "the line is NCSA combined format followed by the duration in milliseconds");
        assertEquals(List.of(request), resolved, "the client address is resolved from the request being logged");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldAttachEveryFieldAsKeyValuePair() {
        EmbeddedChannel channel = newChannel();
        FullHttpRequest request = get("/items?id=1");
        request.headers().set(HttpHeaderNames.REFERER, "https://example.org/");
        request.headers().set(HttpHeaderNames.USER_AGENT, "curl/8.7.1");

        channel.writeInbound(request);
        channel.advanceTimeBy(12, TimeUnit.MILLISECONDS);
        channel.writeOutbound(ok("hello"));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("remote_ip", CLIENT);
        expected.put("method", "GET");
        expected.put("path", "/items");
        expected.put("query", "id=1");
        expected.put("protocol", "HTTP/1.1");
        expected.put("status", 200);
        expected.put("bytes_out", 5L);
        expected.put("duration_ms", 12L);
        expected.put("referer", "https://example.org/");
        expected.put("user_agent", "curl/8.7.1");
        assertEquals(expected, keyValues(log.single()),
            "each field is a key-value pair, with no trace_id when no observation served the request");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogDashForAbsentQueryRefererAndUserAgent() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(get("/ping"));
        channel.writeOutbound(ok(""));

        LoggingEvent event = log.single();
        assertEquals(CLIENT + " - - [25/Oct/2026:10:15:30 +0000] \"GET /ping HTTP/1.1\" 200 - \"-\" \"-\" 0",
            event.getMessage(), "an absent value and an empty body are written as -");
        Map<String, Object> pairs = keyValues(event);
        assertEquals("-", pairs.get("query"), "pairs: " + pairs);
        assertEquals("-", pairs.get("referer"), "pairs: " + pairs);
        assertEquals("-", pairs.get("user_agent"), "pairs: " + pairs);
        assertEquals(0L, pairs.get("bytes_out"), "bytes_out is a number in the pairs; pairs: " + pairs);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogFinalStatusOnceAfterContinue() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(get("/upload"));
        channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
        channel.writeOutbound(ok("done"));

        assertEquals(200, keyValues(log.single()).get("status"),
            "a 1xx is an interim answer, so only the final response is logged");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldSumBodyBytesOfStreamedResponse() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(get("/stream"));
        channel.writeOutbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        channel.writeOutbound(new DefaultHttpContent(ascii("abc")));
        channel.writeOutbound(new DefaultLastHttpContent(ascii("de")));

        assertEquals(5L, keyValues(log.single()).get("bytes_out"), "every part's body counts");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldMeasureDurationToCompletionOfLastWrite() {
        HeldWrites held = new HeldWrites();
        EmbeddedChannel channel = new EmbeddedChannel(held, newHandler());
        channel.freezeTime();

        channel.writeInbound(get("/slow"));
        channel.advanceTimeBy(5, TimeUnit.MILLISECONDS);
        channel.writeAndFlush(ok("x"));
        channel.advanceTimeBy(20, TimeUnit.MILLISECONDS);

        assertEquals(List.of(), log.messages(), "the line waits for the last write to complete");
        held.settle();
        assertEquals(25L, keyValues(log.single()).get("duration_ms"),
            "duration runs from the request head to completion of the last write");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogDashesForUnparseableRequestLine() {
        EmbeddedChannel channel = newCodecChannel();

        channel.writeInbound(wire("GET / HTTX/1.1\r\nHost: x\r\nUser-Agent: curl/8\r\n\r\n"));

        Map<String, Object> pairs = keyValues(log.single());
        assertEquals(400, pairs.get("status"), "pairs: " + pairs);
        assertRequestFieldsAreDashes(pairs);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogDashesForInitialLineOverLimit() {
        EmbeddedChannel channel = newCodecChannel();

        channel.writeInbound(wire("GET /" + "a".repeat(200) + " HTTP/1.1\r\nHost: x\r\n\r\n"));

        Map<String, Object> pairs = keyValues(log.single());
        assertEquals(414, pairs.get("status"), "pairs: " + pairs);
        assertRequestFieldsAreDashes(pairs);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogDashesForHeaderBlockOverLimit() {
        EmbeddedChannel channel = newCodecChannel();

        channel.writeInbound(wire("GET /ping HTTP/1.1\r\nUser-Agent: curl/8\r\nX-Pad: " + "a".repeat(300)
            + "\r\n\r\n"));

        LoggingEvent event = log.single();
        Map<String, Object> pairs = keyValues(event);
        assertEquals(431, pairs.get("status"), "pairs: " + pairs);
        assertRequestFieldsAreDashes(pairs);
        assertTrue(event.getMessage().contains("\"- - -\" 431"),
            "the request line of a failed head is logged as dashes; was: " + event.getMessage());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogRealRequestLineForBodyThatFailsToDecode() {
        EmbeddedChannel channel = newCodecChannel();

        channel.writeInbound(wire("POST /upload HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n"));

        Map<String, Object> pairs = keyValues(log.single());
        assertEquals(400, pairs.get("status"), "pairs: " + pairs);
        assertEquals("POST", pairs.get("method"), "the head decoded cleanly, so it is the real one; pairs: " + pairs);
        assertEquals("/upload", pairs.get("path"), "pairs: " + pairs);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogBodyLimitRejection() {
        EmbeddedChannel channel = new EmbeddedChannel(newHandler(),
            new HttpRequestBodyLimitHandler(10, 100, Duration.ofSeconds(5)));
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        HttpUtil.setContentLength(request, 1000);

        channel.writeInbound(request);

        assertEquals(413, keyValues(log.single()).get("status"),
            "a rejection written by the body limit below this step is logged like any other response");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogUnsupportedExpectationRejection() {
        EmbeddedChannel channel = new EmbeddedChannel(newHandler(),
            new HttpRequestBodyLimitHandler(10, 100, Duration.ofSeconds(5)));
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        request.headers().set(HttpHeaderNames.EXPECT, "something-else");
        HttpUtil.setContentLength(request, 5);

        channel.writeInbound(request);

        assertEquals(417, keyValues(log.single()).get("status"), "messages: " + log.messages());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldCountNoBytesForHeadRequest() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/items"));
        channel.writeOutbound(ok("hello"));

        LoggingEvent event = log.single();
        assertEquals(0L, keyValues(event).get("bytes_out"),
            "HttpServerCodec drops a HEAD response's body, so none of it reached the wire");
        assertTrue(event.getMessage().contains("\" 200 - \""), "was: " + event.getMessage());
        channel.finishAndReleaseAll();
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 205, 304})
    void shouldCountNoBytesForStatusWithoutBody(int status) {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(get("/items"));
        channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
            ascii("hello")));

        assertEquals(0L, keyValues(log.single()).get("bytes_out"),
            "HttpResponseEncoder drops the body of a " + status + ", so none of it reached the wire");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogNothingForRequestClosedBeforeAnyResponse() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(get("/ping"));
        channel.close();

        assertEquals(List.of(), log.messages(), "a request that never got a response has no status to log");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogStatusAndBytesWrittenWhenConnectionDropsMidResponse() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(get("/stream"));
        channel.writeOutbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        channel.writeOutbound(new DefaultHttpContent(ascii("abc")));
        channel.close();

        Map<String, Object> pairs = keyValues(log.single());
        assertEquals(200, pairs.get("status"), "pairs: " + pairs);
        assertEquals(3L, pairs.get("bytes_out"), "pairs: " + pairs);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogOnceWhenLastWriteFails() {
        EmbeddedChannel channel = new EmbeddedChannel(new FailingWrites(), newHandler());

        channel.writeInbound(get("/ping"));
        channel.writeAndFlush(ok("hello"));

        Map<String, Object> pairs = keyValues(log.single());
        assertEquals(200, pairs.get("status"), "pairs: " + pairs);
        assertEquals(0L, pairs.get("bytes_out"), "a body that failed to write was not written; pairs: " + pairs);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogNothingMoreForWriteAfterConnectionDropped() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(get("/stream"));
        channel.writeOutbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        channel.close();
        channel.writeAndFlush(new DefaultLastHttpContent(ascii("late")));

        assertEquals(1, log.events.size(), "the dropped exchange was already logged; got " + log.messages());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogNothingForRequestRefusedWhileDraining() {
        HttpConnectionRegistry registry =
            new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
        EmbeddedChannel channel = new EmbeddedChannel(new HttpDrainHandler(registry), newHandler());
        registry.register(channel);
        registry.beginDrain();

        channel.writeInbound(get("/ping"));

        assertEquals(List.of(), log.messages(), "a refused request gets no response, so it has no line");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldEscapeQuotesBackslashesAndLineBreaksInMessageOnly() {
        EmbeddedChannel channel = newChannel();
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
            "/a\"b\\c?q=\"x\"", Unpooled.EMPTY_BUFFER,
            DefaultHttpHeadersFactory.headersFactory().withValidation(false),
            DefaultHttpHeadersFactory.trailersFactory());
        request.headers().set(HttpHeaderNames.REFERER, "r\r\nforged");
        request.headers().set(HttpHeaderNames.USER_AGENT, "ua\"\\");

        channel.writeInbound(request);
        channel.writeOutbound(ok(""));

        LoggingEvent event = log.single();
        assertEquals(CLIENT + " - - [25/Oct/2026:10:15:30 +0000] \"GET /a\\\"b\\\\c?q=\\\"x\\\" HTTP/1.1\" 200 -"
                + " \"r\\r\\nforged\" \"ua\\\"\\\\\" 0", event.getMessage(),
            "a request must not be able to end the quoted field or the line it is logged on; backslash too, or"
                + " a \\\" it sent would read as an escaped quote and still close the field");
        Map<String, Object> pairs = keyValues(event);
        assertEquals("/a\"b\\c", pairs.get("path"), "structured formatters escape the pairs themselves");
        assertEquals("q=\"x\"", pairs.get("query"), "pairs: " + pairs);
        assertEquals("r\r\nforged", pairs.get("referer"), "pairs: " + pairs);
        assertEquals("ua\"\\", pairs.get("user_agent"), "pairs: " + pairs);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogDashForClientAddressThatIsNotIpLiteralInMessageOnly() {
        String forged = "1.2.3.4 - - [01/Jan/2000:00:00:00 +0000] \"GET /admin HTTP/1.1\" 200";
        EmbeddedChannel channel = new EmbeddedChannel(new HttpAccessLogHandler((request, connection) -> forged,
            log, CLOCK));
        channel.freezeTime();

        channel.writeInbound(get("/ping"));
        channel.writeOutbound(ok(""));

        LoggingEvent event = log.single();
        assertEquals("- - - [25/Oct/2026:10:15:30 +0000] \"GET /ping HTTP/1.1\" 200 - \"-\" \"-\" 0",
            event.getMessage(), "a forwarded value is client-controlled, and the unquoted field cannot be escaped");
        assertEquals(forged, keyValues(event).get("remote_ip"), "structured formatters escape the pairs themselves");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldLogIpv6ClientAddressAsIs() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpAccessLogHandler((request, connection) -> "2001:db8::1",
            log, CLOCK));
        channel.freezeTime();

        channel.writeInbound(get("/ping"));
        channel.writeOutbound(ok(""));

        assertTrue(log.single().getMessage().startsWith("2001:db8::1 - - ["), log.single().getMessage());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldResolveNoClientWhileInfoIsDisabled() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpAccessLogHandler((request, connection) -> {
            resolved.add(request);
            return CLIENT;
        }, NOPLogger.NOP_LOGGER, CLOCK));

        channel.writeInbound(get("/ping"));
        channel.writeOutbound(ok(""));

        assertEquals(List.of(), resolved, "the resolver runs on the event loop, for a line that will not be written");
        assertNull(channel.attr(HttpAccessLogHandler.EXCHANGE).get(), "no exchange is opened for the trace id");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldAttachTraceIdSetOnExchange() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(get("/ping"));
        channel.attr(HttpAccessLogHandler.EXCHANGE).get().setTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
        channel.writeOutbound(ok(""));

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", keyValues(log.single()).get("trace_id"));
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldForgetExchangeOnceItsResponseEnds() {
        EmbeddedChannel channel = newChannel();

        channel.writeInbound(get("/ping"));
        channel.writeOutbound(ok(""));

        assertNull(channel.attr(HttpAccessLogHandler.EXCHANGE).get(),
            "an idle connection must not keep the last request's fields reachable");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldPassResponseThroughWithoutLoggingWhenNoRequestIsOpen() {
        EmbeddedChannel channel = newChannel();

        channel.writeOutbound(ok("hello"));

        assertEquals(List.of(), log.messages(), "a response with no request head above it has nothing to describe");
        FullHttpResponse passed = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, passed.status());
        passed.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    private EmbeddedChannel newCodecChannel() {
        return new EmbeddedChannel(
            new HttpServerCodec(128, 256, 256),
            newHandler(),
            new HttpDecoderFailureHandler(),
            new ReleaseEverything(),
            new HttpExceptionHandler());
    }

    private static void assertRequestFieldsAreDashes(Map<String, Object> pairs) {
        for (String key : List.of("method", "path", "query", "protocol", "referer", "user_agent")) {
            assertEquals("-", pairs.get(key), "nothing is taken from a head that failed to decode: " + key
                + "; pairs: " + pairs);
        }
        assertEquals("-", pairs.get("remote_ip"),
            "the socket peer, not the resolver, which would read headers off the failed head; pairs: " + pairs);
    }

    private static FullHttpRequest get(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    private static FullHttpResponse ok(String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            ascii(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
        return response;
    }

    private static ByteBuf ascii(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    }

    private static ByteBuf wire(String request) {
        return ascii(request);
    }

    private static final class ReleaseEverything extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
        }
    }

    private static final class FailingWrites extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setFailure(new IOException("Connection reset by peer"));
        }
    }

    private static final class HeldWrites extends ChannelOutboundHandlerAdapter {

        private final List<ChannelPromise> unsettled = new ArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof LastHttpContent || msg instanceof ByteBuf) {
                ReferenceCountUtil.release(msg);
                unsettled.add(promise);
                return;
            }
            ctx.write(msg, promise);
        }

        void settle() {
            unsettled.forEach(ChannelPromise::setSuccess);
            unsettled.clear();
        }
    }
}
