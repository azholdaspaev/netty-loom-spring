package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyHttpServletResponseTest {

    @Test
    void sendErrorDiscardsAnyPreviouslyWrittenBody() throws Exception {
        var response = new NettyHttpServletResponse();
        response.getWriter().write("partial output written before the error");

        response.sendError(HttpResponseStatus.FORBIDDEN.code());

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertEquals(HttpResponseStatus.FORBIDDEN.code(), httpResponse.status().code());
        assertEquals("", httpResponse.content().toString(StandardCharsets.UTF_8));
    }

    @Test
    void sendErrorClearsStaleContentLengthHeader() throws Exception {
        var response = new NettyHttpServletResponse();
        response.setContentLength(100);
        response.getWriter().write("a body that will be discarded by sendError");

        response.sendError(HttpResponseStatus.FORBIDDEN.code());

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        // Empty body must not be advertised with the stale Content-Length: 100, which would
        // make the client hang waiting for bytes that never arrive.
        assertEquals(0, httpResponse.content().readableBytes());
        assertEquals("0", httpResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH));
    }

    @Test
    void resetBufferDiscardsContentBufferedInTheWriter() throws Exception {
        var response = new NettyHttpServletResponse();
        // Writer uses autoFlush=false, so these chars sit in the writer's encoder buffer,
        // not yet flushed to the body — resetBuffer() must discard them, not just body's bytes.
        response.getWriter().write("buffered but not yet flushed to the body");

        response.resetBuffer();

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertEquals(0, httpResponse.content().readableBytes());
    }

    @Test
    void addCookieWritesSetCookieHeader() throws Exception {
        var response = new NettyHttpServletResponse();
        response.addCookie(new Cookie("foo", "bar"));

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertTrue(httpResponse.headers().get(HttpHeaderNames.SET_COOKIE).startsWith("foo=bar"));
    }

    @Test
    void addCookieWritesOneHeaderLinePerCookie() throws Exception {
        var response = new NettyHttpServletResponse();
        response.addCookie(new Cookie("a", "1"));
        response.addCookie(new Cookie("b", "2"));

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertEquals(2, httpResponse.headers().getAll(HttpHeaderNames.SET_COOKIE).size());
    }

    @Test
    void addCookieWritesEmptyValue() throws Exception {
        var response = new NettyHttpServletResponse();
        response.addCookie(new Cookie("empty", ""));

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertTrue(httpResponse.headers().get(HttpHeaderNames.SET_COOKIE).contains("empty="));
    }

    @Test
    void addCookieWritesEmptyStringForNullValue() throws Exception {
        var response = new NettyHttpServletResponse();
        // The standard delete-cookie idiom passes a null value; jakarta Cookie permits it.
        response.addCookie(new Cookie("logout", null));

        FullHttpResponse httpResponse = assertDoesNotThrow(response::toFullHttpResponse);
        assertTrue(httpResponse.headers().get(HttpHeaderNames.SET_COOKIE).startsWith("logout="));
    }

    @Test
    void addCookieWritesAttributesWithSessionGuard() throws Exception {
        var response = new NettyHttpServletResponse();
        Cookie cookie = new Cookie("sid", "xyz");
        cookie.setPath("/app");
        cookie.setDomain("example.com");
        cookie.setMaxAge(3600);
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        String setCookie = httpResponse.headers().get(HttpHeaderNames.SET_COOKIE);
        assertTrue(setCookie.contains("Path=/app"));
        assertTrue(setCookie.contains("Domain=example.com"));
        assertTrue(setCookie.contains("Max-Age=3600"));
        assertTrue(setCookie.contains("Secure"));
        assertTrue(setCookie.contains("HTTPOnly"));
    }

    @Test
    void addCookieOmitsMaxAgeForSessionCookie() throws Exception {
        var response = new NettyHttpServletResponse();
        // Default maxAge is -1 (session cookie): must not emit Max-Age.
        response.addCookie(new Cookie("sid", "xyz"));

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertFalse(httpResponse.headers().get(HttpHeaderNames.SET_COOKIE).contains("Max-Age"));
    }

    @Test
    void addCookieMapsSameSiteCaseInsensitively() throws Exception {
        var response = new NettyHttpServletResponse();
        Cookie cookie = new Cookie("sid", "xyz");
        cookie.setAttribute("SameSite", "strict");
        response.addCookie(cookie);

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertTrue(httpResponse.headers().get(HttpHeaderNames.SET_COOKIE).contains("SameSite=Strict"));
    }

    @Test
    void addCookieTrimsPaddedSameSite() throws Exception {
        var response = new NettyHttpServletResponse();
        Cookie cookie = new Cookie("sid", "xyz");
        // A padded value must not silently drop SameSite (weakening the intended policy).
        cookie.setAttribute("SameSite", " Strict ");
        response.addCookie(cookie);

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertTrue(httpResponse.headers().get(HttpHeaderNames.SET_COOKIE).contains("SameSite=Strict"));
    }

    @Test
    void addCookieMapsPartitionedAttribute() throws Exception {
        var response = new NettyHttpServletResponse();
        Cookie cookie = new Cookie("sid", "xyz");
        // CHIPS: presence of the Partitioned attribute (empty value) marks a partitioned cookie.
        cookie.setAttribute("Partitioned", "");
        response.addCookie(cookie);

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertTrue(httpResponse.headers().get(HttpHeaderNames.SET_COOKIE).contains("Partitioned"));
    }

    @Test
    void addCookieThrowsForInvalidCookieValue() {
        var response = new NettyHttpServletResponse();
        // The space is an invalid RFC 6265 cookie-octet. ServerCookieEncoder.STRICT rejects it,
        // and addCookie deliberately lets that IllegalArgumentException propagate (fail-fast,
        // matching Tomcat's Rfc6265CookieProcessor) rather than dropping or mangling the cookie.
        assertThrows(IllegalArgumentException.class,
            () -> response.addCookie(new Cookie("sid", "invalid value")));
    }

    @Test
    void addCookieIgnoresVersion() throws Exception {
        var response = new NettyHttpServletResponse();
        Cookie cookie = new Cookie("sid", "xyz");
        cookie.setVersion(1);

        assertDoesNotThrow(() -> response.addCookie(cookie));

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        String setCookie = httpResponse.headers().get(HttpHeaderNames.SET_COOKIE);
        assertTrue(setCookie.startsWith("sid=xyz"));
        // RFC 6265 / Netty have no Version field — it is never emitted.
        assertFalse(setCookie.contains("Version"));
    }
}
