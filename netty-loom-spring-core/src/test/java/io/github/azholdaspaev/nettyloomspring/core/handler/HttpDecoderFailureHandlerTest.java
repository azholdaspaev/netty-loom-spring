package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real {@link HttpServerCodec} at deliberately small limits, so raw over-limit bytes drive a genuine
 * decode failure (issue #138). A hand-set {@code DecoderResult} would prove only that the handler reads
 * the field, not that Netty's decoder ever sets it.
 */
class HttpDecoderFailureHandlerTest {

    private static final int MAX_INITIAL_LINE_LENGTH = 128;
    private static final int MAX_HEADER_SIZE = 256;
    private static final int MAX_CHUNK_SIZE = 256;
    private static final int MAX_BODY_BYTES = 1024;

    @Test
    void shouldRejectAHeaderOverTheLimitWith431() {
        DispatchProbe probe = new DispatchProbe();
        EmbeddedChannel channel = newChannel(probe);

        channel.writeInbound(wire("GET /ping HTTP/1.1\r\nHost: x\r\nX-Pad: " + "a".repeat(300) + "\r\n\r\n"));

        String response = readWire(channel);
        assertTrue(response.startsWith(statusLine(HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE)),
            "an over-limit header must be answered with 431, not served with the header silently dropped; was: "
                + response);
        assertEquals(List.of(), probe.dispatched,
            "the request must not reach the application, whose view of it is missing the oversized header");
        assertFalse(channel.isOpen(),
            "the decoder discards everything after a bad message, so the connection cannot be reused");
    }

    @Test
    void shouldRejectAnInitialLineOverTheLimitWith414() {
        DispatchProbe probe = new DispatchProbe();
        EmbeddedChannel channel = newChannel(probe);

        channel.writeInbound(wire("GET /" + "a".repeat(200) + " HTTP/1.1\r\nHost: x\r\n\r\n"));

        String response = readWire(channel);
        assertTrue(response.startsWith(statusLine(HttpResponseStatus.REQUEST_URI_TOO_LONG)),
            "an over-limit initial line must be answered with 414; was: " + response);
        assertEquals(List.of(), probe.dispatched,
            "the application must not be asked for the /bad-request URI Netty synthesises for an unparseable line");
        assertFalse(channel.isOpen(),
            "the decoder discards everything after a bad message, so the connection cannot be reused");
    }

    @Test
    void shouldPassAValidRequestThroughToTheDispatcher() {
        DispatchProbe probe = new DispatchProbe();
        EmbeddedChannel channel = newChannel(probe);

        channel.writeInbound(wire("GET /ping HTTP/1.1\r\nHost: x\r\n\r\n"));

        assertEquals(List.of("/ping"), probe.dispatched, "a request within the limits must be dispatched untouched");
        assertNull(channel.readOutbound(), "nothing may be written for a request the handler did not reject");
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReleaseTheRejectedRequest() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpDecoderFailureHandler(), new HttpExceptionHandler());
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ping");
        request.setDecoderResult(DecoderResult.failure(new TooLongHttpHeaderException("header too big")));

        channel.writeInbound(request);

        assertEquals(0, request.refCnt(), "the rejected request is consumed here, so nothing downstream will free it");
        channel.finishAndReleaseAll();
    }

    private static EmbeddedChannel newChannel(DispatchProbe probe) {
        return new EmbeddedChannel(
            new HttpServerCodec(MAX_INITIAL_LINE_LENGTH, MAX_HEADER_SIZE, MAX_CHUNK_SIZE),
            new HttpObjectAggregator(MAX_BODY_BYTES),
            new HttpDecoderFailureHandler(),
            probe,
            new HttpExceptionHandler());
    }

    private static ByteBuf wire(String request) {
        return Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII);
    }

    private static String statusLine(HttpResponseStatus status) {
        return HttpVersion.HTTP_1_1 + " " + status + "\r\n";
    }

    private static String readWire(EmbeddedChannel channel) {
        ByteBuf encoded = channel.readOutbound();
        assertNotNull(encoded, "the rejection must reach the wire");
        String response = encoded.toString(StandardCharsets.US_ASCII);
        encoded.release();
        return response;
    }

    /**
     * Stands in for the dispatcher below this handler, and terminates the inbound chain as it does.
     */
    private static final class DispatchProbe extends ChannelInboundHandlerAdapter {

        private final List<String> dispatched = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest request) {
                dispatched.add(request.uri());
            }
            ReferenceCountUtil.release(msg);
        }
    }
}
