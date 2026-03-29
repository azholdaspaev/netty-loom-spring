package io.github.azholdaspaev.nettyloom.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultNettyHttpRequestConverterTest {

    @Mock
    private FullHttpRequest msg;

    private DefaultNettyHttpRequestConverter converter;
    private ByteBuf contentBuffer;

    @BeforeEach
    void setUp() {
        converter = new DefaultNettyHttpRequestConverter();
    }

    @AfterEach
    void tearDown() {
        if (contentBuffer != null && contentBuffer.refCnt() > 0) {
            contentBuffer.release();
        }
    }

    // ── HTTP method conversion ────────────────────────────────────────

    @Test
    void shouldConvertGetMethod() {
        givenRequest(io.netty.handler.codec.http.HttpMethod.GET);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.method()).isEqualTo(HttpMethod.GET);
    }

    @Test
    void shouldConvertPostMethod() {
        givenRequest(io.netty.handler.codec.http.HttpMethod.POST);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.method()).isEqualTo(HttpMethod.POST);
    }

    @Test
    void shouldConvertPutMethod() {
        givenRequest(io.netty.handler.codec.http.HttpMethod.PUT);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.method()).isEqualTo(HttpMethod.PUT);
    }

    @Test
    void shouldConvertPatchMethod() {
        givenRequest(io.netty.handler.codec.http.HttpMethod.PATCH);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.method()).isEqualTo(HttpMethod.PATCH);
    }

    @Test
    void shouldConvertDeleteMethod() {
        givenRequest(io.netty.handler.codec.http.HttpMethod.DELETE);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.method()).isEqualTo(HttpMethod.DELETE);
    }

    @Test
    void shouldConvertHeadMethod() {
        givenRequest(io.netty.handler.codec.http.HttpMethod.HEAD);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.method()).isEqualTo(HttpMethod.HEAD);
    }

    @Test
    void shouldConvertOptionsMethod() {
        givenRequest(io.netty.handler.codec.http.HttpMethod.OPTIONS);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.method()).isEqualTo(HttpMethod.OPTIONS);
    }

    @Test
    void shouldThrowForUnsupportedMethod() {
        when(msg.method()).thenReturn(io.netty.handler.codec.http.HttpMethod.TRACE);

        assertThatThrownBy(() -> converter.convert(msg)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── URI conversion ────────────────────────────────────────────────

    @Test
    void shouldConvertUri() {
        givenRequest(io.netty.handler.codec.http.HttpMethod.GET, "/api/v1/test?param=value");

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.uri()).isEqualTo("/api/v1/test?param=value");
    }

    // ── Header conversion ─────────────────────────────────────────────

    @Test
    void shouldConvertHeaders() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.add("Content-Type", "application/json");
        headers.add("Accept", "text/html");
        headers.add("Accept", "application/json");
        givenRequest(io.netty.handler.codec.http.HttpMethod.GET, "/", headers, new byte[0]);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.headers().get("Content-Type")).containsExactly("application/json");
        assertThat(result.headers().get("Accept")).containsExactly("text/html", "application/json");
    }

    // ── Body conversion ───────────────────────────────────────────────

    @Test
    void shouldConvertRequestBody() {
        byte[] body = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        givenRequest(io.netty.handler.codec.http.HttpMethod.POST, "/", new DefaultHttpHeaders(), body);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.body()).isEqualTo(body);
    }

    @Test
    void shouldConvertEmptyBody() {
        givenRequest(io.netty.handler.codec.http.HttpMethod.GET);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.body()).isEmpty();
    }

    // ── Keep-alive conversion ─────────────────────────────────────────

    @Test
    void shouldConvertKeepAliveRequest() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.add("Connection", "keep-alive");
        givenRequest(io.netty.handler.codec.http.HttpMethod.GET, "/", headers, new byte[0]);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.keepAlive()).isTrue();
    }

    @Test
    void shouldConvertNonKeepAliveRequest() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.add("Connection", "close");
        givenRequest(io.netty.handler.codec.http.HttpMethod.GET, "/", headers, new byte[0]);

        NettyHttpRequest result = converter.convert(msg);

        assertThat(result.keepAlive()).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void givenRequest(io.netty.handler.codec.http.HttpMethod method) {
        givenRequest(method, "/", new DefaultHttpHeaders(), new byte[0]);
    }

    private void givenRequest(io.netty.handler.codec.http.HttpMethod method, String uri) {
        givenRequest(method, uri, new DefaultHttpHeaders(), new byte[0]);
    }

    private void givenRequest(
            io.netty.handler.codec.http.HttpMethod method, String uri, HttpHeaders headers, byte[] body) {
        contentBuffer = body.length > 0 ? Unpooled.copiedBuffer(body) : Unpooled.EMPTY_BUFFER;
        when(msg.method()).thenReturn(method);
        when(msg.uri()).thenReturn(uri);
        when(msg.headers()).thenReturn(headers);
        when(msg.content()).thenReturn(contentBuffer);
        lenient().when(msg.protocolVersion()).thenReturn(HttpVersion.HTTP_1_1);
    }
}
