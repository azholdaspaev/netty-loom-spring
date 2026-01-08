package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettyHttpServletResponseTest {

    private NettyHttpServletResponse response;

    @BeforeEach
    void setUp() {
        response = new NettyHttpServletResponse();
    }

    @Nested
    class StatusCode {

        @Test
        void shouldDefaultToOk() {
            // Given - new response

            // When
            int status = response.getStatus();

            // Then
            assertThat(status).isEqualTo(200);
        }

        @Test
        void shouldSetStatus() {
            // Given

            // When
            response.setStatus(404);

            // Then
            assertThat(response.getStatus()).isEqualTo(404);
        }

        @Test
        void shouldSetStatusInNettyResponse() {
            // Given
            response.setStatus(201);

            // When
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.status().code()).isEqualTo(201);
        }
    }

    @Nested
    class Headers {

        @Test
        void shouldSetHeader() {
            // Given

            // When
            response.setHeader("X-Custom", "value");

            // Then
            assertThat(response.getHeader("X-Custom")).isEqualTo("value");
        }

        @Test
        void shouldAddHeader() {
            // Given
            response.addHeader("Accept", "text/html");

            // When
            response.addHeader("Accept", "application/json");

            // Then
            Collection<String> values = response.getHeaders("Accept");
            assertThat(values).containsExactly("text/html", "application/json");
        }

        @Test
        void shouldOverwriteHeaderWithSet() {
            // Given
            response.setHeader("X-Custom", "value1");

            // When
            response.setHeader("X-Custom", "value2");

            // Then
            assertThat(response.getHeader("X-Custom")).isEqualTo("value2");
        }

        @Test
        void shouldCheckHeaderExists() {
            // Given
            response.setHeader("X-Custom", "value");

            // When/Then
            assertThat(response.containsHeader("X-Custom")).isTrue();
            assertThat(response.containsHeader("X-Missing")).isFalse();
        }

        @Test
        void shouldReturnHeaderNames() {
            // Given
            response.setHeader("X-Header1", "value1");
            response.setHeader("X-Header2", "value2");

            // When
            Collection<String> names = response.getHeaderNames();

            // Then
            assertThat(names).contains("X-Header1", "X-Header2");
        }

        @Test
        void shouldSetIntHeader() {
            // Given

            // When
            response.setIntHeader("X-Count", 42);

            // Then
            assertThat(response.getHeader("X-Count")).isEqualTo("42");
        }

        @Test
        void shouldCopyHeadersToNettyResponse() {
            // Given
            response.setHeader("X-Custom", "value");

            // When
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.headers().get("X-Custom")).isEqualTo("value");
        }
    }

    @Nested
    class OutputStream {

        @Test
        void shouldWriteToOutputStream() throws Exception {
            // Given
            String content = "Hello World";

            // When
            ServletOutputStream os = response.getOutputStream();
            os.write(content.getBytes(StandardCharsets.UTF_8));
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            byte[] body = new byte[nettyResponse.content().readableBytes()];
            nettyResponse.content().readBytes(body);
            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(content);
        }

        @Test
        void shouldThrowWhenWriterAlreadyCalled() throws Exception {
            // Given
            response.getWriter();

            // When/Then
            assertThatThrownBy(() -> response.getOutputStream())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("getWriter()");
        }
    }

    @Nested
    class Writer {

        @Test
        void shouldWriteToWriter() throws Exception {
            // Given
            String content = "Hello World";

            // When
            PrintWriter writer = response.getWriter();
            writer.print(content);
            writer.flush();
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            byte[] body = new byte[nettyResponse.content().readableBytes()];
            nettyResponse.content().readBytes(body);
            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(content);
        }

        @Test
        void shouldThrowWhenOutputStreamAlreadyCalled() throws Exception {
            // Given
            response.getOutputStream();

            // When/Then
            assertThatThrownBy(() -> response.getWriter())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("getOutputStream()");
        }
    }

    @Nested
    class ContentType {

        @Test
        void shouldSetContentType() {
            // Given

            // When
            response.setContentType("application/json");

            // Then
            assertThat(response.getContentType()).isEqualTo("application/json");
        }

        @Test
        void shouldAddContentTypeToNettyResponse() {
            // Given
            response.setContentType("application/json");

            // When
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.headers().get(HttpHeaderNames.CONTENT_TYPE))
                    .startsWith("application/json");
        }

        @Test
        void shouldIncludeCharsetForTextTypes() {
            // Given
            response.setContentType("text/html");

            // When
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.headers().get(HttpHeaderNames.CONTENT_TYPE))
                    .isEqualTo("text/html; charset=UTF-8");
        }

        @Test
        void shouldParseCharsetFromContentType() {
            // Given

            // When
            response.setContentType("text/html; charset=ISO-8859-1");

            // Then
            assertThat(response.getCharacterEncoding()).isEqualTo("ISO-8859-1");
        }
    }

    @Nested
    class CharacterEncoding {

        @Test
        void shouldDefaultToUtf8() {
            // Given - new response

            // When
            String encoding = response.getCharacterEncoding();

            // Then
            assertThat(encoding).isEqualTo("UTF-8");
        }

        @Test
        void shouldSetCharacterEncoding() {
            // Given

            // When
            response.setCharacterEncoding("ISO-8859-1");

            // Then
            assertThat(response.getCharacterEncoding()).isEqualTo("ISO-8859-1");
        }
    }

    @Nested
    class ContentLength {

        @Test
        void shouldSetContentLengthInNettyResponse() throws Exception {
            // Given
            String content = "Hello World";
            response.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));

            // When
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH))
                    .isEqualTo(String.valueOf(content.length()));
        }
    }

    @Nested
    class SendError {

        @Test
        void shouldSetStatusOnSendError() throws Exception {
            // Given

            // When
            response.sendError(404, "Not Found");

            // Then
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(response.isCommitted()).isTrue();
        }

        @Test
        void shouldThrowWhenCommitted() throws Exception {
            // Given
            response.sendError(404);

            // When/Then
            assertThatThrownBy(() -> response.sendError(500))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldSetStatusMessageInNettyResponse() throws Exception {
            // Given
            response.sendError(404, "Resource Not Found");

            // When
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.status().code()).isEqualTo(404);
            assertThat(nettyResponse.status().reasonPhrase()).isEqualTo("Resource Not Found");
        }
    }

    @Nested
    class SendRedirect {

        @Test
        void shouldSetLocationHeader() throws Exception {
            // Given

            // When
            response.sendRedirect("http://example.com/new-location");

            // Then
            assertThat(response.getStatus()).isEqualTo(302);
            assertThat(response.getHeader("Location")).isEqualTo("http://example.com/new-location");
        }

        @Test
        void shouldCommitResponse() throws Exception {
            // Given

            // When
            response.sendRedirect("http://example.com");

            // Then
            assertThat(response.isCommitted()).isTrue();
        }
    }

    @Nested
    class Cookies {

        @Test
        void shouldAddCookieToResponse() {
            // Given
            Cookie cookie = new Cookie("session", "abc123");
            cookie.setMaxAge(3600);
            cookie.setPath("/");
            cookie.setHttpOnly(true);

            // When
            response.addCookie(cookie);
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            String setCookie = nettyResponse.headers().get(HttpHeaderNames.SET_COOKIE);
            assertThat(setCookie).contains("session=abc123");
            assertThat(setCookie).contains("Path=/");
            assertThat(setCookie.toLowerCase()).contains("httponly");
        }

        @Test
        void shouldAddMultipleCookies() {
            // Given
            response.addCookie(new Cookie("cookie1", "value1"));
            response.addCookie(new Cookie("cookie2", "value2"));

            // When
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.headers().getAll(HttpHeaderNames.SET_COOKIE)).hasSize(2);
        }
    }

    @Nested
    class ResetAndResetBuffer {

        @Test
        void shouldResetBuffer() throws Exception {
            // Given
            response.getOutputStream().write("Initial content".getBytes(StandardCharsets.UTF_8));

            // When
            response.resetBuffer();
            response.getOutputStream().write("New content".getBytes(StandardCharsets.UTF_8));
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            byte[] body = new byte[nettyResponse.content().readableBytes()];
            nettyResponse.content().readBytes(body);
            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("New content");
        }

        @Test
        void shouldResetEverything() throws Exception {
            // Given
            response.setStatus(404);
            response.setHeader("X-Custom", "value");
            response.setContentType("application/json");
            response.getOutputStream().write("content".getBytes(StandardCharsets.UTF_8));

            // When
            response.reset();

            // Then
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("X-Custom")).isNull();
            assertThat(response.getContentType()).isNull();
        }

        @Test
        void shouldThrowResetWhenCommitted() throws Exception {
            // Given
            response.flushBuffer();

            // When/Then
            assertThatThrownBy(() -> response.reset())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldThrowResetBufferWhenCommitted() throws Exception {
            // Given
            response.flushBuffer();

            // When/Then
            assertThatThrownBy(() -> response.resetBuffer())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class FlushBuffer {

        @Test
        void shouldCommitResponse() throws Exception {
            // Given
            assertThat(response.isCommitted()).isFalse();

            // When
            response.flushBuffer();

            // Then
            assertThat(response.isCommitted()).isTrue();
        }
    }

    @Nested
    class Locale {

        @Test
        void shouldSetLocale() {
            // Given

            // When
            response.setLocale(java.util.Locale.GERMANY);

            // Then
            assertThat(response.getLocale()).isEqualTo(java.util.Locale.GERMANY);
        }

        @Test
        void shouldSetContentLanguageHeader() {
            // Given

            // When
            response.setLocale(java.util.Locale.GERMANY);
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.headers().get(HttpHeaderNames.CONTENT_LANGUAGE))
                    .isEqualTo("de-DE");
        }
    }

    @Nested
    class ToNettyResponse {

        @Test
        void shouldBuildValidNettyResponse() throws Exception {
            // Given
            response.setStatus(201);
            response.setContentType("application/json");
            response.setHeader("X-Custom", "value");
            PrintWriter writer = response.getWriter();
            writer.print("{\"status\":\"created\"}");

            // When
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.status()).isEqualTo(HttpResponseStatus.CREATED);
            assertThat(nettyResponse.headers().get(HttpHeaderNames.CONTENT_TYPE))
                    .startsWith("application/json");
            assertThat(nettyResponse.headers().get("X-Custom")).isEqualTo("value");

            byte[] body = new byte[nettyResponse.content().readableBytes()];
            nettyResponse.content().readBytes(body);
            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"status\":\"created\"}");
        }

        @Test
        void shouldIncludeServerHeaderWhenConfigured() {
            // Given
            response = new NettyHttpServletResponse(() -> "Netty-Loom/1.0");

            // When
            FullHttpResponse nettyResponse = response.toNettyResponse();

            // Then
            assertThat(nettyResponse.headers().get(HttpHeaderNames.SERVER)).isEqualTo("Netty-Loom/1.0");
        }
    }
}
