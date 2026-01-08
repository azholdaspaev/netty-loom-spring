package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettyHttpServletRequestTest {

    private FullHttpRequest nettyRequest;
    private NettyHttpServletRequest request;

    @BeforeEach
    void setUp() {
        nettyRequest = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/test/path?param1=value1&param2=value2"
        );
        request = new NettyHttpServletRequest(nettyRequest, null, null, "");
    }

    @Nested
    class URIParsing {

        @Test
        void shouldReturnCorrectRequestURI() {
            // Given - request with query string

            // When
            String uri = request.getRequestURI();

            // Then
            assertThat(uri).isEqualTo("/test/path");
        }

        @Test
        void shouldReturnCorrectQueryString() {
            // Given - request with query string

            // When
            String query = request.getQueryString();

            // Then
            assertThat(query).isEqualTo("param1=value1&param2=value2");
        }

        @Test
        void shouldReturnNullQueryStringWhenNone() {
            // Given
            nettyRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/test/path"
            );
            request = new NettyHttpServletRequest(nettyRequest, null, null, "");

            // When
            String query = request.getQueryString();

            // Then
            assertThat(query).isNull();
        }

        @Test
        void shouldReturnCorrectServletPath() {
            // Given
            request = new NettyHttpServletRequest(nettyRequest, null, null, "/api");

            // When
            String servletPath = request.getServletPath();

            // Then
            assertThat(servletPath).isEqualTo("/test/path");
        }

        @Test
        void shouldReturnFullURIAsServletPathWithoutContextPath() {
            // Given - no context path

            // When
            String servletPath = request.getServletPath();

            // Then
            assertThat(servletPath).isEqualTo("/test/path");
        }

        @Test
        void shouldBuildCorrectRequestURL() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.HOST, "example.com:8080");

            // When
            StringBuffer url = request.getRequestURL();

            // Then
            assertThat(url.toString()).isEqualTo("http://example.com:8080/test/path");
        }
    }

    @Nested
    class HeaderAccess {

        @Test
        void shouldReturnSingleHeader() {
            // Given
            nettyRequest.headers().set("X-Custom-Header", "custom-value");

            // When
            String value = request.getHeader("X-Custom-Header");

            // Then
            assertThat(value).isEqualTo("custom-value");
        }

        @Test
        void shouldReturnNullForMissingHeader() {
            // Given - no header set

            // When
            String value = request.getHeader("X-Missing");

            // Then
            assertThat(value).isNull();
        }

        @Test
        void shouldReturnAllHeaderValues() {
            // Given
            nettyRequest.headers().add("Accept", "text/html");
            nettyRequest.headers().add("Accept", "application/json");

            // When
            Enumeration<String> values = request.getHeaders("Accept");
            List<String> list = Collections.list(values);

            // Then
            assertThat(list).containsExactly("text/html", "application/json");
        }

        @Test
        void shouldReturnAllHeaderNames() {
            // Given
            nettyRequest.headers().set("X-Header1", "value1");
            nettyRequest.headers().set("X-Header2", "value2");

            // When
            Enumeration<String> names = request.getHeaderNames();
            List<String> list = Collections.list(names);

            // Then
            assertThat(list).contains("X-Header1", "X-Header2");
        }

        @Test
        void shouldParseIntHeader() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1234");

            // When
            int length = request.getIntHeader(HttpHeaderNames.CONTENT_LENGTH.toString());

            // Then
            assertThat(length).isEqualTo(1234);
        }

        @Test
        void shouldReturnMinusOneForMissingIntHeader() {
            // Given - no header

            // When
            int value = request.getIntHeader("X-Missing");

            // Then
            assertThat(value).isEqualTo(-1);
        }
    }

    @Nested
    class ParameterParsing {

        @Test
        void shouldParseQueryParameters() {
            // Given - request with query string

            // When
            String param1 = request.getParameter("param1");
            String param2 = request.getParameter("param2");

            // Then
            assertThat(param1).isEqualTo("value1");
            assertThat(param2).isEqualTo("value2");
        }

        @Test
        void shouldReturnNullForMissingParameter() {
            // Given - request with query string

            // When
            String missing = request.getParameter("missing");

            // Then
            assertThat(missing).isNull();
        }

        @Test
        void shouldReturnAllParameterValues() {
            // Given
            nettyRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/test?color=red&color=blue&color=green"
            );
            request = new NettyHttpServletRequest(nettyRequest, null, null, "");

            // When
            String[] values = request.getParameterValues("color");

            // Then
            assertThat(values).containsExactly("red", "blue", "green");
        }

        @Test
        void shouldReturnParameterMap() {
            // Given - request with query string

            // When
            Map<String, String[]> params = request.getParameterMap();

            // Then
            assertThat(params).containsKey("param1");
            assertThat(params).containsKey("param2");
            assertThat(params.get("param1")).containsExactly("value1");
        }

        @Test
        void shouldParseFormEncodedBody() {
            // Given
            String body = "name=John&city=Boston";
            ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
            nettyRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/test",
                    content
            );
            nettyRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded");
            request = new NettyHttpServletRequest(nettyRequest, null, null, "");

            // When
            String name = request.getParameter("name");
            String city = request.getParameter("city");

            // Then
            assertThat(name).isEqualTo("John");
            assertThat(city).isEqualTo("Boston");
        }

        @Test
        void shouldHandleUrlEncodedParameters() {
            // Given
            nettyRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/test?message=hello%20world&special=%26%3D"
            );
            request = new NettyHttpServletRequest(nettyRequest, null, null, "");

            // When
            String message = request.getParameter("message");
            String special = request.getParameter("special");

            // Then
            assertThat(message).isEqualTo("hello world");
            assertThat(special).isEqualTo("&=");
        }
    }

    @Nested
    class InputStream {

        @Test
        void shouldReadBodyViaInputStream() throws Exception {
            // Given
            String body = "Request body content";
            ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
            nettyRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/test",
                    content
            );
            request = new NettyHttpServletRequest(nettyRequest, null, null, "");

            // When
            ServletInputStream is = request.getInputStream();
            byte[] bytes = is.readAllBytes();

            // Then
            assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(body);
        }

        @Test
        void shouldThrowWhenReaderAlreadyCalled() throws Exception {
            // Given
            request.getReader();

            // When/Then
            assertThatThrownBy(() -> request.getInputStream())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("getReader()");
        }
    }

    @Nested
    class Reader {

        @Test
        void shouldReadBodyViaReader() throws Exception {
            // Given
            String body = "Request body content";
            ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
            nettyRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/test",
                    content
            );
            request = new NettyHttpServletRequest(nettyRequest, null, null, "");

            // When
            BufferedReader reader = request.getReader();
            String line = reader.readLine();

            // Then
            assertThat(line).isEqualTo(body);
        }

        @Test
        void shouldThrowWhenInputStreamAlreadyCalled() throws Exception {
            // Given
            request.getInputStream();

            // When/Then
            assertThatThrownBy(() -> request.getReader())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("getInputStream()");
        }
    }

    @Nested
    class ContentMetadata {

        @Test
        void shouldReturnContentType() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");

            // When
            String contentType = request.getContentType();

            // Then
            assertThat(contentType).isEqualTo("application/json");
        }

        @Test
        void shouldReturnContentLength() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1024");

            // When
            int length = request.getContentLength();

            // Then
            assertThat(length).isEqualTo(1024);
        }

        @Test
        void shouldReturnMinusOneForMissingContentLength() {
            // Given - no content length header

            // When
            int length = request.getContentLength();

            // Then
            assertThat(length).isEqualTo(-1);
        }

        @Test
        void shouldParseCharsetFromContentType() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=ISO-8859-1");

            // When
            String encoding = request.getCharacterEncoding();

            // Then
            assertThat(encoding).isEqualTo("ISO-8859-1");
        }

        @Test
        void shouldAllowSettingCharacterEncoding() throws Exception {
            // Given

            // When
            request.setCharacterEncoding("UTF-16");

            // Then
            assertThat(request.getCharacterEncoding()).isEqualTo("UTF-16");
        }
    }

    @Nested
    class Attributes {

        @Test
        void shouldSetAndGetAttribute() {
            // Given
            Object value = new Object();

            // When
            request.setAttribute("myAttr", value);

            // Then
            assertThat(request.getAttribute("myAttr")).isSameAs(value);
        }

        @Test
        void shouldRemoveAttribute() {
            // Given
            request.setAttribute("myAttr", "value");

            // When
            request.removeAttribute("myAttr");

            // Then
            assertThat(request.getAttribute("myAttr")).isNull();
        }

        @Test
        void shouldRemoveAttributeWhenSetToNull() {
            // Given
            request.setAttribute("myAttr", "value");

            // When
            request.setAttribute("myAttr", null);

            // Then
            assertThat(request.getAttribute("myAttr")).isNull();
        }

        @Test
        void shouldReturnAttributeNames() {
            // Given
            request.setAttribute("attr1", "value1");
            request.setAttribute("attr2", "value2");

            // When
            Enumeration<String> names = request.getAttributeNames();
            List<String> list = Collections.list(names);

            // Then
            assertThat(list).containsExactlyInAnyOrder("attr1", "attr2");
        }
    }

    @Nested
    class MethodAndProtocol {

        @Test
        void shouldReturnGetMethod() {
            // Given - GET request

            // When
            String method = request.getMethod();

            // Then
            assertThat(method).isEqualTo("GET");
        }

        @Test
        void shouldReturnPostMethod() {
            // Given
            nettyRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/test"
            );
            request = new NettyHttpServletRequest(nettyRequest, null, null, "");

            // When
            String method = request.getMethod();

            // Then
            assertThat(method).isEqualTo("POST");
        }

        @Test
        void shouldReturnProtocol() {
            // Given - HTTP/1.1

            // When
            String protocol = request.getProtocol();

            // Then
            assertThat(protocol).isEqualTo("HTTP/1.1");
        }
    }

    @Nested
    class ServerInfo {

        @Test
        void shouldParseServerNameFromHostHeader() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.HOST, "example.com:8080");

            // When
            String serverName = request.getServerName();

            // Then
            assertThat(serverName).isEqualTo("example.com");
        }

        @Test
        void shouldParseServerPortFromHostHeader() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.HOST, "example.com:8080");

            // When
            int serverPort = request.getServerPort();

            // Then
            assertThat(serverPort).isEqualTo(8080);
        }

        @Test
        void shouldReturnDefaultPortWhenNotSpecified() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.HOST, "example.com");

            // When
            int serverPort = request.getServerPort();

            // Then
            assertThat(serverPort).isEqualTo(80);
        }

        @Test
        void shouldReturnScheme() {
            // Given - HTTP request

            // When
            String scheme = request.getScheme();

            // Then
            assertThat(scheme).isEqualTo("http");
        }
    }

    @Nested
    class Locales {

        @Test
        void shouldParseAcceptLanguageHeader() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "en-US, de-DE;q=0.8");

            // When
            Enumeration<Locale> locales = request.getLocales();
            List<Locale> list = Collections.list(locales);

            // Then
            assertThat(list).hasSize(2);
            assertThat(list.get(0).getLanguage()).isEqualTo("en");
            assertThat(list.get(1).getLanguage()).isEqualTo("de");
        }

        @Test
        void shouldReturnDefaultLocaleWhenNoHeader() {
            // Given - no Accept-Language header

            // When
            Locale locale = request.getLocale();

            // Then
            assertThat(locale).isEqualTo(Locale.getDefault());
        }
    }

    @Nested
    class Cookies {

        @Test
        void shouldParseCookieHeader() {
            // Given
            nettyRequest.headers().set(HttpHeaderNames.COOKIE, "session=abc123; user=john");

            // When
            jakarta.servlet.http.Cookie[] cookies = request.getCookies();

            // Then
            assertThat(cookies).hasSize(2);
            assertThat(cookies[0].getName()).isEqualTo("session");
            assertThat(cookies[0].getValue()).isEqualTo("abc123");
            assertThat(cookies[1].getName()).isEqualTo("user");
            assertThat(cookies[1].getValue()).isEqualTo("john");
        }

        @Test
        void shouldReturnNullWhenNoCookies() {
            // Given - no cookies

            // When
            jakarta.servlet.http.Cookie[] cookies = request.getCookies();

            // Then
            assertThat(cookies).isNull();
        }
    }
}
