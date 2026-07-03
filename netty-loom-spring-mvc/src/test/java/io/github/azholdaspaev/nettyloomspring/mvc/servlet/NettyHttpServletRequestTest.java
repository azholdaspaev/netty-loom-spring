package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyHttpServletRequestTest {

    private static NettyHttpServletRequest request(HttpConnectionMetadata connection, NettyServletContext context) {
        return new NettyHttpServletRequest(
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x"),
            connection,
            context);
    }

    private static NettyHttpServletRequest request(String uri, String host, HttpConnectionMetadata connection) {
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        if (host != null) {
            nettyRequest.headers().set(HttpHeaderNames.HOST, host);
        }
        return new NettyHttpServletRequest(nettyRequest, connection, new DefaultNettyServletContext());
    }

    private static NettyHttpServletRequest formRequest(String uri, byte[] body, HttpConnectionMetadata connection) {
        var nettyRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, uri, Unpooled.wrappedBuffer(body));
        nettyRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        return new NettyHttpServletRequest(nettyRequest, connection, new DefaultNettyServletContext());
    }

    private static NettyHttpServletRequest requestWithAcceptLanguage(String acceptLanguage, HttpConnectionMetadata connection) {
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x");
        nettyRequest.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, acceptLanguage);
        return new NettyHttpServletRequest(nettyRequest, connection, new DefaultNettyServletContext());
    }

    @Test
    void networkGettersFromConnection() {
        var context = new DefaultNettyServletContext();
        var request = request(new HttpConnectionMetadata("203.0.113.7", 54321, "198.51.100.2", 8080, false), context);

        assertEquals("203.0.113.7", request.getRemoteAddr());
        assertEquals("203.0.113.7", request.getRemoteHost());
        assertEquals(54321, request.getRemotePort());
        assertEquals("198.51.100.2", request.getLocalAddr());
        assertEquals("198.51.100.2", request.getLocalName());
        assertEquals(8080, request.getLocalPort());
        assertEquals("http", request.getScheme());
        assertFalse(request.isSecure());
        assertSame(context, request.getServletContext());
    }

    @Test
    void remoteAndLocalHostsAreNotReverseDnsResolvedForIpv6() {
        var request = request(
            new HttpConnectionMetadata("::1", 9999, "::1", 8080, false),
            new DefaultNettyServletContext());

        assertEquals("::1", request.getRemoteAddr());
        assertEquals("::1", request.getRemoteHost());
        assertEquals("::1", request.getLocalAddr());
        assertEquals("::1", request.getLocalName());
    }

    @Test
    void protocolReflectsHttpVersion() {
        var request = request(new HttpConnectionMetadata("", 0, "", 0, false), new DefaultNettyServletContext());

        assertEquals("HTTP/1.1", request.getProtocol());
    }

    @Test
    void serverNamePortFromHostHeader() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false);

        var hostOnly = request("/x", "example.com", insecure);
        assertEquals("example.com", hostOnly.getServerName());
        assertEquals(80, hostOnly.getServerPort());

        var hostPort = request("/x", "example.com:8443", insecure);
        assertEquals("example.com", hostPort.getServerName());
        assertEquals(8443, hostPort.getServerPort());

        var ipv6Port = request("/x", "[::1]:8080", insecure);
        assertEquals("[::1]", ipv6Port.getServerName());
        assertEquals(8080, ipv6Port.getServerPort());

        var ipv6NoPort = request("/x", "[::1]", insecure);
        assertEquals("[::1]", ipv6NoPort.getServerName());
        assertEquals(80, ipv6NoPort.getServerPort());

        var noHost = request("/x", null, insecure);
        assertEquals("198.51.100.9", noHost.getServerName());
        assertEquals(7070, noHost.getServerPort());

        var underscored = request("/x", "redis_master:6379", insecure);
        assertEquals("redis_master", underscored.getServerName());
        assertEquals(6379, underscored.getServerPort());

        var blank = request("/x", "", insecure);
        assertEquals("198.51.100.9", blank.getServerName());
        assertEquals(7070, blank.getServerPort());
    }

    @Test
    void ipv6ServerNameIsBracketedWhileLocalAddrStaysRaw() {
        // No Host header: serverName falls back to the local socket. For URL/authority use the IPv6
        // address must be bracketed, but the Servlet-spec numeric getLocalAddr()/getLocalName() must
        // remain the raw, unbracketed IP.
        var request = request("/x", null, new HttpConnectionMetadata("::1", 9999, "::1", 8080, false));

        assertEquals("[::1]", request.getServerName());
        assertEquals("http://[::1]:8080/x", request.getRequestURL().toString());
        assertEquals("::1", request.getLocalAddr());
        assertEquals("::1", request.getLocalName());
    }

    @Test
    void requestUrlWithoutHostAndEmptyLocalAddrOmitsAuthority() {
        // No Host header and a non-Inet local address (empty localAddr): the URL must not become the
        // malformed "http:///x" with an empty authority.
        var request = request("/x", null, new HttpConnectionMetadata("", 0, "", 0, false));

        assertEquals("http:/x", request.getRequestURL().toString());
    }

    @Test
    void localesParseQOrderAndDefault() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false);

        var ordered = requestWithAcceptLanguage("da, en-gb;q=0.8, en;q=0.7", insecure);
        assertEquals(Locale.forLanguageTag("da"), ordered.getLocale());
        assertEquals(
            List.of(Locale.forLanguageTag("da"), Locale.forLanguageTag("en-gb"), Locale.forLanguageTag("en")),
            Collections.list(ordered.getLocales()));

        var equalWeight = requestWithAcceptLanguage("en, fr", insecure);
        assertEquals(
            List.of(Locale.forLanguageTag("en"), Locale.forLanguageTag("fr")),
            Collections.list(equalWeight.getLocales()));

        var rejected = requestWithAcceptLanguage("en;q=0", insecure);
        assertEquals(Locale.getDefault(), rejected.getLocale());
        assertEquals(List.of(Locale.getDefault()), Collections.list(rejected.getLocales()));

        var absent = request("/x", null, insecure);
        assertEquals(Locale.getDefault(), absent.getLocale());
        assertEquals(List.of(Locale.getDefault()), Collections.list(absent.getLocales()));

        var wildcardOnly = requestWithAcceptLanguage("*", insecure);
        assertEquals(Locale.getDefault(), wildcardOnly.getLocale());

        var malformed = requestWithAcceptLanguage("not a valid range!!!", insecure);
        assertEquals(Locale.getDefault(), malformed.getLocale());

        assertNotNull(ordered.getLocale());
        assertTrue(Collections.list(ordered.getLocales()).size() >= 1);
    }

    @Test
    void localesAreCachedOnFirstAccessAndReused() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false);
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x");
        nettyRequest.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "da, en-gb;q=0.8, en;q=0.7");
        var request = new NettyHttpServletRequest(nettyRequest, insecure, new DefaultNettyServletContext());

        assertEquals(Locale.forLanguageTag("da"), request.getLocale());
        List<Locale> first = Collections.list(request.getLocales());

        // Mutating the header after the first resolution must not change the result: parsing happens once.
        nettyRequest.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "fr");
        assertEquals(Locale.forLanguageTag("da"), request.getLocale());
        assertEquals(first, Collections.list(request.getLocales()));
        assertEquals(
            List.of(Locale.forLanguageTag("da"), Locale.forLanguageTag("en-gb"), Locale.forLanguageTag("en")),
            first);
    }

    @Test
    void requestUrlOmitsDefaultPortAndQuery() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false);
        var secure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, true);

        var defaultHttp = request("/foo", "example.com", insecure);
        assertEquals("http://example.com/foo", defaultHttp.getRequestURL().toString());

        var defaultHttps = request("/foo", "example.com", secure);
        assertEquals("https://example.com/foo", defaultHttps.getRequestURL().toString());

        var combined = request("/app/x?q=1", "example.com:8443", secure);
        assertEquals("https://example.com:8443/app/x", combined.getRequestURL().toString());
        assertInstanceOf(StringBuffer.class, combined.getRequestURL());
    }

    @Test
    void paramsParseLazilyOnFirstAccess() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false);
        var request = formRequest("/x?a=1", "b=2".getBytes(StandardCharsets.UTF_8), insecure);

        assertEquals("1", request.getParameter("a"));
        assertEquals("2", request.getParameter("b"));
        assertEquals(2, request.getParameterMap().size());
        assertSame(request.getParameterMap(), request.getParameterMap());
    }

    @Test
    void queryStringDecodedAsUtf8IndependentOfBodyEncoding() throws Exception {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false);
        // %C3%A9 is the UTF-8 encoding of "é". The query must always decode as UTF-8, while the
        // form body must honor the body charset set via setCharacterEncoding.
        var request = formRequest("/x?q=%C3%A9", "name=%C3%A9".getBytes(StandardCharsets.US_ASCII), insecure);
        request.setCharacterEncoding("ISO-8859-1");

        assertEquals("é", request.getParameter("q"));
        // Bytes 0xC3 0xA9 decoded as ISO-8859-1 -> "Ã©", proving the body charset applies to the body only.
        assertEquals("Ã©", request.getParameter("name"));
    }

    @Test
    void readerDefaultCharsetMatchesParameterParsing() throws Exception {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false);
        // No charset set anywhere: getReader() and parameter parsing must agree on the default.
        byte[] body = new byte[] {'v', '=', (byte) 0xE9};

        var viaReader = formRequest("/x", body, insecure);
        var viaParam = formRequest("/x", body, insecure);

        String paramValue = viaParam.getParameter("v");
        assertEquals("�", paramValue);
        assertEquals("v=" + paramValue, viaReader.getReader().readLine());
    }

    @Test
    void setEncodingBeforeReadAffectsParamsThenLocks() throws Exception {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false);
        // 0xE9 decodes to "é" in ISO-8859-1, and to the replacement char in UTF-8.
        byte[] body = new byte[] {'n', 'a', 'm', 'e', '=', (byte) 0xE9};

        var beforeRead = formRequest("/x", body, insecure);
        assertNull(beforeRead.getCharacterEncoding());
        beforeRead.setCharacterEncoding("ISO-8859-1");
        assertEquals("ISO-8859-1", beforeRead.getCharacterEncoding());
        assertEquals("é", beforeRead.getParameter("name"));
        // First getParameter locked the encoding -> later setCharacterEncoding is a no-op.
        beforeRead.setCharacterEncoding("UTF-8");
        assertEquals("ISO-8859-1", beforeRead.getCharacterEncoding());

        var viaReader = formRequest("/x", body, insecure);
        viaReader.setCharacterEncoding("UTF-8");
        assertEquals("name=�", viaReader.getReader().readLine());
        // getReader locked the encoding too.
        viaReader.setCharacterEncoding("ISO-8859-1");
        assertEquals("UTF-8", viaReader.getCharacterEncoding());

        var badCharset = formRequest("/x", body, insecure);
        assertThrows(UnsupportedEncodingException.class, () -> badCharset.setCharacterEncoding("no-such-charset!!"));

        // getInputStream consumes bytes, not the charset, so it must NOT lock the encoding.
        var viaStream = formRequest("/x", body, insecure);
        viaStream.getInputStream();
        viaStream.setCharacterEncoding("ISO-8859-1");
        assertEquals("ISO-8859-1", viaStream.getCharacterEncoding());
        assertEquals("é", viaStream.getParameter("name"));
    }

    private static NettyHttpServletRequest cookieRequest(String... cookieHeaders) {
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        for (String header : cookieHeaders) {
            nettyRequest.headers().add(HttpHeaderNames.COOKIE, header);
        }
        return new NettyHttpServletRequest(
            nettyRequest,
            new HttpConnectionMetadata("", 0, "", 0, false),
            new DefaultNettyServletContext());
    }

    @Test
    void getCookiesParsesSingleCookie() {
        Cookie[] cookies = cookieRequest("foo=bar").getCookies();

        assertEquals(1, cookies.length);
        assertEquals("foo", cookies[0].getName());
        assertEquals("bar", cookies[0].getValue());
    }

    @Test
    void getCookiesPreservesOrderOfMultiplePairs() {
        Cookie[] cookies = cookieRequest("a=1; b=2; c=3").getCookies();

        assertEquals(3, cookies.length);
        assertEquals("a", cookies[0].getName());
        assertEquals("b", cookies[1].getName());
        assertEquals("c", cookies[2].getName());
    }

    @Test
    void getCookiesReadsMultipleCookieHeaders() {
        Cookie[] cookies = cookieRequest("a=1", "b=2").getCookies();

        assertEquals(2, cookies.length);
        assertEquals("a", cookies[0].getName());
        assertEquals("1", cookies[0].getValue());
        assertEquals("b", cookies[1].getName());
        assertEquals("2", cookies[1].getValue());
    }

    @Test
    void getCookiesKeepsDuplicateNamesInOrder() {
        Cookie[] cookies = cookieRequest("foo=1; foo=2").getCookies();

        assertEquals(2, cookies.length);
        assertEquals("foo", cookies[0].getName());
        assertEquals("1", cookies[0].getValue());
        assertEquals("foo", cookies[1].getName());
        assertEquals("2", cookies[1].getValue());
    }

    @Test
    void getCookiesPreservesValuesVerbatim() {
        Cookie[] encodedCookies = cookieRequest("foo=hello%20world").getCookies();

        assertEquals(1, encodedCookies.length);
        assertEquals("hello%20world", encodedCookies[0].getValue());

        Cookie[] emptyCookies = cookieRequest("foo=").getCookies();

        assertEquals(1, emptyCookies.length);
        assertEquals("foo", emptyCookies[0].getName());
        assertEquals("", emptyCookies[0].getValue());
    }

    @Test
    void getCookiesReturnsNullForMalformedHeaderWithoutThrowing() {
        assertNull(assertDoesNotThrow(() -> cookieRequest("=;;garbage").getCookies()));
        assertNull(assertDoesNotThrow(() -> cookieRequest("").getCookies()));
    }

    @Test
    void getCookiesReturnsNullWhenNoCookieHeader() {
        assertNull(cookieRequest().getCookies());
    }

    @Test
    void getCookiesReturnsDefensiveCopyButCachesElements() {
        NettyHttpServletRequest request = cookieRequest("a=1; b=2");

        Cookie[] first = request.getCookies();
        Cookie[] second = request.getCookies();

        // Each call hands back a fresh array (parity with getParameterValues), so a caller that
        // mutates the returned array cannot corrupt a later getCookies() in the same request.
        assertNotSame(first, second);
        first[0] = null;
        assertNotNull(request.getCookies()[0]);
        // The shallow copy still shares element instances: cookies are parsed once, then cached.
        assertSame(second[1], request.getCookies()[1]);
    }
}
