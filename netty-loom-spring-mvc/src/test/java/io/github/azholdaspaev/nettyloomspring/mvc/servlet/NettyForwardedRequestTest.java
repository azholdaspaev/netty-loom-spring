package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyForwardedRequestTest {

    @Test
    void shouldShareMetadataAcrossGettersAndCookieCreation() {
        var response = new NettyHttpServletResponse();
        var request = request(new DefaultNettyServletContext(), response, List.of("10.0.0.0/8"));
        request.ensureMetadataResolved();
        assertEquals("https", request.getScheme());
        assertTrue(request.isSecure());
        assertEquals("app.example", request.getServerName());
        assertEquals(443, request.getServerPort());
        assertEquals("203.0.113.7", request.getRemoteAddr());
        assertEquals("203.0.113.7", request.getRemoteHost());
        assertEquals(0, request.getRemotePort());
        assertEquals("https://app.example/path", request.getRequestURL().toString());
        assertFalse(request.getServletConnection().isSecure());
        assertEquals("10.0.0.1", request.getLocalAddr());
        assertEquals(8080, request.getLocalPort());
        request.getSession();
        assertTrue(response.getHeader("Set-Cookie").contains("Secure"));
        request.changeSessionId();
        assertTrue(response.getHeader("Set-Cookie").contains("Secure"));
        assertEquals(1, response.getHeaders("Set-Cookie").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Forwarded", "X-Forwarded-Proto", "x-forwarded-port",
        "X-Forwarded-Host", "X-Forwarded-For", "X-Forwarded-Prefix", "X-Forwarded-Ssl"})
    void shouldHideForwardingHeadersThroughEveryAccessor(String name) {
        var request = request(new DefaultNettyServletContext(), new NettyHttpServletResponse(), List.of());
        assertNull(request.getHeader(name));
        assertFalse(request.getHeaders(name).hasMoreElements());
        assertTrue(Collections.list(request.getHeaderNames()).stream().noneMatch(n -> n.equalsIgnoreCase(name)));
        assertEquals(-1, request.getIntHeader(name));
        assertEquals(-1L, request.getDateHeader(name));
    }

    @Test
    void shouldPreventSpringFilterFromReapplyingUntrustedHeaders() throws Exception {
        var response = new NettyHttpServletResponse();
        var request = request(new DefaultNettyServletContext(), response, List.of());
        new ForwardedHeaderFilter().doFilter(request, response, (filtered, _) -> {
            assertSame(request, filtered);
            assertFalse(filtered.isSecure());
        });
        request.getSession();
        assertFalse(response.getHeader("Set-Cookie").contains("Secure"));
    }

    @Test
    void shouldKeepConfiguredSecureCookieOnPlainRequest() {
        var context = new DefaultNettyServletContext();
        context.getSessionCookieConfig().setSecure(true);
        var response = new NettyHttpServletResponse();
        var request = request(context, response, List.of());
        assertFalse(request.isSecure());
        request.getSession();
        request.changeSessionId();
        assertTrue(response.getHeader("Set-Cookie").contains("Secure"));
    }

    @Test
    void shouldFreezeMetadataWithoutChangingNettyHeaders() {
        var netty = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        netty.headers().set("Host", "internal:8080");
        String forwarded = "for=203.0.113.7;proto=https;host=app.example";
        netty.headers().set("Forwarded", forwarded);
        var request = new NettyHttpServletRequest(netty, InputStream.nullInputStream(),
            new HttpConnectionMetadata("10.0.0.2", 1234, "10.0.0.1", 8080, false, "channel"),
            new DefaultNettyServletContext(), new NettyHttpServletResponse(),
            new NettyRequestMetadataResolver(List.of("10.0.0.0/8")));
        request.ensureMetadataResolved();
        assertEquals(forwarded, netty.headers().get("Forwarded"));
        netty.headers().set("Forwarded", "broken");
        netty.headers().set("Host", "changed:80");
        assertTrue(request.isSecure());
        assertEquals("app.example", request.getServerName());
        assertEquals(443, request.getServerPort());
        assertEquals("203.0.113.7", request.getRemoteAddr());
    }

    private static NettyHttpServletRequest request(DefaultNettyServletContext context,
                                                    NettyHttpServletResponse response, List<String> trusted) {
        var netty = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path?q=1");
        netty.headers().set("Host", "internal:8080");
        netty.headers().set("Forwarded", "for=203.0.113.7;proto=https;host=app.example");
        netty.headers().set("X-Forwarded-For", "198.51.100.3");
        netty.headers().set("X-Forwarded-Host", "other.example");
        netty.headers().set("X-Forwarded-Proto", "http");
        netty.headers().set("X-Forwarded-Port", "80");
        netty.headers().set("X-Forwarded-Ssl", "on");
        netty.headers().set("X-Forwarded-Prefix", "/evil");
        return new NettyHttpServletRequest(netty, InputStream.nullInputStream(),
            new HttpConnectionMetadata("10.0.0.2", 1234, "10.0.0.1", 8080, false, "channel"),
            context, response, new NettyRequestMetadataResolver(trusted));
    }
}
