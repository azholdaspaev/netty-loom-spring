package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSessionIdListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpHeaders;

import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.github.azholdaspaev.nettyloomspring.mvc.servlet.UnknownSessionIds.OTHER_UNKNOWN_SESSION_ID;
import static io.github.azholdaspaev.nettyloomspring.mvc.servlet.UnknownSessionIds.UNKNOWN_SESSION_ID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyHttpServletRequestTest {

    private static final int ID_THREADS = 8;
    // Tens of thousands, not hundreds: a 500-request probe passed against a racy counter (#278).
    private static final int IDS_PER_THREAD = 50_000;

    private static NettyHttpServletRequest request(HttpConnectionMetadata connection, NettyServletContext context) {
        return new NettyHttpServletRequest(
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x"), InputStream.nullInputStream(),
            connection,
            context,
            new NettyHttpServletResponse());
    }

    private static NettyHttpServletRequest request(String uri, String host, HttpConnectionMetadata connection) {
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        if (host != null) {
            nettyRequest.headers().set(HttpHeaderNames.HOST, host);
        }
        return new NettyHttpServletRequest(
            nettyRequest, InputStream.nullInputStream(), connection, new DefaultNettyServletContext(), new NettyHttpServletResponse());
    }

    private static NettyHttpServletRequest formRequest(String uri, byte[] body, HttpConnectionMetadata connection) {
        return formRequest(HttpMethod.POST, uri, body, connection);
    }

    private static NettyHttpServletRequest formRequest(HttpMethod method, String uri, byte[] body,
                                                       HttpConnectionMetadata connection) {
        var nettyRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, method, uri, Unpooled.wrappedBuffer(body));
        nettyRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
        return new NettyHttpServletRequest(
            nettyRequest, new ByteArrayInputStream(body), connection, new DefaultNettyServletContext(),
            new NettyHttpServletResponse());
    }

    private static NettyHttpServletRequest requestWithAcceptLanguage(String acceptLanguage, HttpConnectionMetadata connection) {
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x");
        nettyRequest.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, acceptLanguage);
        return new NettyHttpServletRequest(
            nettyRequest, InputStream.nullInputStream(), connection, new DefaultNettyServletContext(), new NettyHttpServletResponse());
    }

    private static NettyHttpServletRequest requestWithContext(String uri, String contextPath) {
        var context = new DefaultNettyServletContext();
        context.setContextPath(contextPath);
        return new NettyHttpServletRequest(
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri), InputStream.nullInputStream(),
            new HttpConnectionMetadata("", 0, "", 0, false, ""),
            context,
            new NettyHttpServletResponse());
    }

    @Test
    void shouldReadContextPathAndServletPathFromServletContext() {
        var inContext = requestWithContext("/app/hello?x=1", "/app");
        assertEquals("/app", inContext.getContextPath());
        assertEquals("/app/hello", inContext.getRequestURI());
        assertEquals("/hello", inContext.getServletPath());

        var atContextRoot = requestWithContext("/app", "/app");
        assertEquals("/app", atContextRoot.getContextPath());
        assertEquals("/app", atContextRoot.getRequestURI());
        assertEquals("", atContextRoot.getServletPath());
    }

    @Test
    void shouldLeavePathGettersUnchangedForEmptyContext() {
        var noContext = requestWithContext("/hello", "");
        assertEquals("", noContext.getContextPath());
        assertEquals("/hello", noContext.getRequestURI());
        assertEquals("/hello", noContext.getServletPath());
    }

    @Test
    void shouldReportContextRootAndPrefixAsWithinContext() {
        assertTrue(requestWithContext("/app", "/app").isWithinContext());
        assertTrue(requestWithContext("/app/hello", "/app").isWithinContext());
        assertFalse(requestWithContext("/application", "/app").isWithinContext());
    }

    @Test
    void shouldReportOutOfContextUriAsNotWithinContext() {
        assertFalse(requestWithContext("/other", "/app").isWithinContext());
        assertFalse(requestWithContext("/ap", "/app").isWithinContext());
    }

    @Test
    void shouldReportEveryUriAsWithinRootContext() {
        var root = requestWithContext("/anything", "");
        assertTrue(root.isWithinContext());
        assertEquals("/anything", root.getServletPath());
    }

    @Test
    void shouldReturnEmptyServletPathForUriShorterThanContextPath() {
        // Blindly stripping the prefix would throw StringIndexOutOfBoundsException here.
        var request = requestWithContext("/ap", "/app");
        assertEquals("", request.getServletPath());
    }

    @Test
    void shouldReportRequestUriPathAsSent() {
        var request = requestWithContext("/files/a%2Fb/%2541", "");

        assertEquals("/files/a%2Fb/%2541", request.getRequestURI(),
            "getRequestURI() reports the URI undecoded; percent-decoding it here makes Spring decode twice");
    }

    @Test
    void shouldKeepRequestUrlPathAsSent() {
        var request = request("/files/a%2Fb", "example.com", INSECURE);

        assertEquals("http://example.com/files/a%2Fb", request.getRequestURL().toString(),
            "getRequestURL() is getRequestURI() with an authority in front, so it is undecoded too");
    }

    @Test
    void shouldDecodeServletPathWhileUriStaysRaw() {
        var request = requestWithContext("/app/a%2Fb", "/app");

        assertEquals("/app/a%2Fb", request.getRequestURI());
        assertTrue(request.isWithinContext());
        assertEquals("/a/b", request.getServletPath(),
            "filter patterns match on this string, so it stays decoded even though the URI above does not");
    }

    @Test
    void shouldTreatEncodedContextPathPrefixAsOutOfContext() {
        assertFalse(requestWithContext("/%61pp/hello", "/app").isWithinContext(),
            "getContextPath() is the configured literal, so Spring would throw on a raw URI it does not prefix");
        assertFalse(requestWithContext("/app%2Fx/hello", "/app").isWithinContext(),
            "an encoded slash inside the prefix is not a segment boundary on the wire, so nothing mounts here");
    }

    @Test
    void shouldReturnNullPathInfoWhenRequestCarriesNoExtraPath() {
        var inContext = requestWithContext("/app/hello", "/app");
        assertNull(inContext.getPathInfo());
        assertNull(inContext.getPathTranslated());
        assertEquals("/hello", inContext.getServletPath());

        var noContext = requestWithContext("/hello", "");
        assertNull(noContext.getPathInfo());
        assertNull(noContext.getPathTranslated());
        assertEquals("/hello", noContext.getServletPath());
    }

    @Test
    void shouldReturnNullAuthenticationAccessorsWhenUnauthenticated() {
        var request = request(INSECURE, new DefaultNettyServletContext());

        assertNull(request.getAuthType());
        assertNull(request.getRemoteUser());
        assertNull(request.getUserPrincipal());
    }

    @Test
    void shouldReadNetworkGettersFromConnection() {
        var context = new DefaultNettyServletContext();
        var request = request(new HttpConnectionMetadata("203.0.113.7", 54321, "198.51.100.2", 8080, false, ""), context);

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
    void shouldNotReverseDnsResolveRemoteAndLocalHostsForIpv6() {
        var request = request(
            new HttpConnectionMetadata("::1", 9999, "::1", 8080, false, ""),
            new DefaultNettyServletContext());

        assertEquals("::1", request.getRemoteAddr());
        assertEquals("::1", request.getRemoteHost());
        assertEquals("::1", request.getLocalAddr());
        assertEquals("::1", request.getLocalName());
    }

    @Test
    void shouldReflectHttpVersionInProtocol() {
        var request = request(new HttpConnectionMetadata("", 0, "", 0, false, ""), new DefaultNettyServletContext());

        assertEquals("HTTP/1.1", request.getProtocol());
    }

    @Test
    void shouldGiveUniqueRequestIdAndNonNullServletConnection() {
        var secure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, true, "conn-7");
        var first = request(secure, new DefaultNettyServletContext());
        var second = request(secure, new DefaultNettyServletContext());

        assertFalse(first.getRequestId().isEmpty(), "getRequestId() must not be the empty string");
        assertNotEquals(first.getRequestId(), second.getRequestId(),
            "two requests must not share a request id (Servlet 6.0 getRequestId: unique within the container)");
        assertEquals(first.getRequestId(), first.getRequestId(), "the id must be stable for the request's lifetime");
        assertEquals("", first.getProtocolRequestId(), "HTTP/1.x defines no protocol request id");

        ServletConnection connection = first.getServletConnection();
        assertNotNull(connection, "getServletConnection() must never be null");
        assertEquals("conn-7", connection.getConnectionId());
        assertEquals("http/1.1", connection.getProtocol(), "the ALPN identification sequence, as the spec requires");
        assertEquals("", connection.getProtocolConnectionId(), "HTTP/1.x defines no protocol connection id");
        assertTrue(connection.isSecure());
        assertFalse(request(INSECURE, new DefaultNettyServletContext()).getServletConnection().isSecure());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldHandOutDistinctRequestIdsWhenConstructedConcurrently() throws InterruptedException {
        var context = new DefaultNettyServletContext();
        Set<String> ids = ConcurrentHashMap.newKeySet();

        NettySessionManagerConcurrencyTest.race(ID_THREADS, () -> {
            for (int i = 0; i < IDS_PER_THREAD; i++) {
                ids.add(request(INSECURE, context).getRequestId());
            }
        });

        assertEquals(ID_THREADS * IDS_PER_THREAD, ids.size(),
            "every request constructed concurrently must get its own id (Servlet 6.0 getRequestId: unique within the container)");
    }

    @Test
    void shouldReadServerNameAndPortFromHostHeader() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");

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
    void shouldBracketIpv6ServerNameWhileLocalAddrStaysRaw() {
        /*
         * No Host header: serverName falls back to the local socket. For URL/authority use the IPv6
         * address must be bracketed, but the Servlet-spec numeric getLocalAddr()/getLocalName() must
         * remain the raw, unbracketed IP.
         */
        var request = request("/x", null, new HttpConnectionMetadata("::1", 9999, "::1", 8080, false, ""));

        assertEquals("[::1]", request.getServerName());
        assertEquals("http://[::1]:8080/x", request.getRequestURL().toString());
        assertEquals("::1", request.getLocalAddr());
        assertEquals("::1", request.getLocalName());
    }

    @Test
    void shouldOmitAuthorityFromRequestUrlWithoutHostAndLocalAddr() {
        /*
         * No Host header and a non-Inet local address (empty localAddr): the URL must not become the
         * malformed "http:///x" with an empty authority.
         */
        var request = request("/x", null, new HttpConnectionMetadata("", 0, "", 0, false, ""));

        assertEquals("http:/x", request.getRequestURL().toString());
    }

    @Test
    void shouldParseLocalesInQOrderAndDefault() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");

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
    void shouldCacheLocalesOnFirstAccessAndReuseThem() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x");
        nettyRequest.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "da, en-gb;q=0.8, en;q=0.7");
        var request = new NettyHttpServletRequest(
            nettyRequest, InputStream.nullInputStream(), insecure, new DefaultNettyServletContext(), new NettyHttpServletResponse());

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
    void shouldOmitDefaultPortAndQueryFromRequestUrl() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
        var secure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, true, "");

        var defaultHttp = request("/foo", "example.com", insecure);
        assertEquals("http://example.com/foo", defaultHttp.getRequestURL().toString());

        var defaultHttps = request("/foo", "example.com", secure);
        assertEquals("https://example.com/foo", defaultHttps.getRequestURL().toString());

        var combined = request("/app/x?q=1", "example.com:8443", secure);
        assertEquals("https://example.com:8443/app/x", combined.getRequestURL().toString());
        assertInstanceOf(StringBuffer.class, combined.getRequestURL());
    }

    @Test
    void shouldParseParamsLazilyOnFirstAccess() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
        var request = formRequest("/x?a=1", "b=2".getBytes(StandardCharsets.UTF_8), insecure);

        assertEquals("1", request.getParameter("a"));
        assertEquals("2", request.getParameter("b"));
        assertEquals(2, request.getParameterMap().size());
        assertSame(request.getParameterMap(), request.getParameterMap());
    }

    @Test
    void shouldNotCorruptGetParameterOnceParameterMapArrayIsMutated() {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
        var request = formRequest("/x?a=1", "b=2".getBytes(StandardCharsets.UTF_8), insecure);

        request.getParameterMap().get("a")[0] = "mutated";

        assertEquals("1", request.getParameter("a"),
            "Servlet 6.0 declares the map immutable; Tomcat's Request.getParameterMap fills its "
                + "locked ParameterMap from Parameters.getParameterValues, which hands out a fresh "
                + "array, so a caller writing into the map never reaches the accessor path");
        assertArrayEquals(new String[] {"1"}, request.getParameterValues("a"));
    }

    @Test
    void shouldNotParseFormParametersForMethodTomcatWouldNotParse() throws Exception {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
        var request = formRequest(HttpMethod.PUT, "/x?a=1", "b=2".getBytes(StandardCharsets.UTF_8), insecure);

        assertEquals("1", request.getParameter("a"), "the query string is parsed on every method");
        assertNull(request.getParameter("b"),
            "Connector.parseBodyMethods is POST alone, which is why Spring ships FormContentFilter "
                + "for the rest; parsing here drains a body the handler is about to read");
        assertEquals("b=2", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
            "a PUT body belongs to whoever reads it, not to the parameter map");
    }

    @Test
    void shouldNotParseFormParametersOnceGetInputStreamClaimedBody() throws Exception {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
        var request = formRequest("/x?a=1", "b=2".getBytes(StandardCharsets.UTF_8), insecure);
        ServletInputStream body = request.getInputStream();
        assertEquals('b', body.read());

        assertEquals("1", request.getParameter("a"), "the query string is parsed either way");
        assertNull(request.getParameter("b"),
            "Tomcat's doParseParameters returns once usingInputStream is set; parsing the tail would "
                + "invent a parameter from \"=2\" and leave the caller's stream at EOF");
        assertEquals('=', body.read(), "the claimed stream must still be where its owner left it");
    }

    @Test
    void shouldReportInputStreamFinishedOnceFormParseDrainedBody() throws Exception {
        var request = bodyRequest("b=2".getBytes(StandardCharsets.UTF_8),
            "Content-Type", "application/x-www-form-urlencoded", "Content-Length", "3");
        assertEquals("2", request.getParameter("b"), "the form parse ran and read all three bytes");

        assertTrue(request.getInputStream().isFinished(),
            "the form parse drained the body, so nothing is left to read; Tomcat's readPostBody "
                + "reads through the same InputBuffer, so IdentityInputFilter.remaining is already zero");
    }

    @Test
    void shouldNotParseFormParametersOnceGetReaderClaimedBody() throws Exception {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
        var request = formRequest("/x?a=1", "b=2".getBytes(StandardCharsets.UTF_8), insecure);
        BufferedReader reader = request.getReader();

        assertNull(request.getParameter("b"),
            "the body belongs to the reader that claimed it");
        assertEquals("b=2", reader.readLine(),
            "parsing behind the reader's back would hand it a body already drained");
    }

    @Test
    void shouldDecodeQueryStringAsUtf8IndependentOfBodyEncoding() throws Exception {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
        /*
         * %C3%A9 is the UTF-8 encoding of "é". The query must always decode as UTF-8, while the
         * form body must honor the body charset set via setCharacterEncoding.
         */
        var request = formRequest("/x?q=%C3%A9", "name=%C3%A9".getBytes(StandardCharsets.US_ASCII), insecure);
        request.setCharacterEncoding("ISO-8859-1");

        assertEquals("é", request.getParameter("q"));
        // Bytes 0xC3 0xA9 decoded as ISO-8859-1 -> "Ã©", proving the body charset applies to the body only.
        assertEquals("Ã©", request.getParameter("name"));
    }

    @Test
    void shouldMatchReaderDefaultCharsetToParameterParsing() throws Exception {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
        // No charset set anywhere: getReader() and parameter parsing must agree on the default.
        byte[] body = new byte[] {'v', '=', (byte) 0xE9};

        var viaReader = formRequest("/x", body, insecure);
        var viaParam = formRequest("/x", body, insecure);

        String paramValue = viaParam.getParameter("v");
        assertEquals("�", paramValue);
        assertEquals("v=" + paramValue, viaReader.getReader().readLine());
    }

    @Test
    void shouldApplyEncodingSetBeforeReadToParamsThenLock() throws Exception {
        var insecure = new HttpConnectionMetadata("198.51.100.2", 1, "198.51.100.9", 7070, false, "");
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
    }

    private static NettyHttpServletRequest bodyRequest(byte[] body, String... headers) {
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/x");
        for (int i = 0; i < headers.length; i += 2) {
            nettyRequest.headers().set(headers[i], headers[i + 1]);
        }
        return new NettyHttpServletRequest(
            nettyRequest, new ByteArrayInputStream(body),
            new HttpConnectionMetadata("", 0, "", 0, false, ""),
            new DefaultNettyServletContext(),
            new NettyHttpServletResponse());
    }

    @Test
    void shouldReportInputStreamFinishedOnceContentLengthIsConsumed() throws Exception {
        var request = bodyRequest("hello".getBytes(StandardCharsets.UTF_8), "Content-Length", "5");
        ServletInputStream body = request.getInputStream();

        assertFalse(body.isFinished(), "five declared bytes are unread");
        assertEquals(5, body.read(new byte[5]));
        assertTrue(body.isFinished(),
            "the declared Content-Length has been handed out, so no read past the end is needed");
    }

    @Test
    void shouldReportInputStreamFinishedWhenRequestDeclaresNoBody() throws Exception {
        var request = bodyRequest(new byte[0]);

        assertTrue(request.getInputStream().isFinished(),
            "a request with neither Content-Length nor Transfer-Encoding has an empty body "
                + "(RFC 9112 6.3); Netty emits its LastHttpContent at once and Tomcat's VoidInputFilter "
                + "answers true from the start");
    }

    @Test
    void shouldIgnoreContentLengthBesideChunkedTransferEncoding() throws Exception {
        var request = bodyRequest("hello".getBytes(StandardCharsets.UTF_8),
            "Content-Length", "0", "Transfer-Encoding", "chunked");
        ServletInputStream body = request.getInputStream();

        assertFalse(body.isFinished(),
            "Transfer-Encoding overrides Content-Length (RFC 9112 6.3), so a chunked body is finished "
                + "only once a read returns -1");
        assertEquals(5, body.read(new byte[5]));
        assertFalse(body.isFinished(), "the zero-chunk has not been seen yet");
        assertEquals(-1, body.read());
        assertTrue(body.isFinished());
    }

    private static NettyHttpServletRequest cookieRequest(String... cookieHeaders) {
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        for (String header : cookieHeaders) {
            nettyRequest.headers().add(HttpHeaderNames.COOKIE, header);
        }
        return new NettyHttpServletRequest(
            nettyRequest, InputStream.nullInputStream(),
            new HttpConnectionMetadata("", 0, "", 0, false, ""),
            new DefaultNettyServletContext(),
            new NettyHttpServletResponse());
    }

    @Test
    void shouldParseSingleCookie() {
        Cookie[] cookies = cookieRequest("foo=bar").getCookies();

        assertEquals(1, cookies.length);
        assertEquals("foo", cookies[0].getName());
        assertEquals("bar", cookies[0].getValue());
    }

    @Test
    void shouldPreserveOrderOfMultipleCookiePairs() {
        Cookie[] cookies = cookieRequest("a=1; b=2; c=3").getCookies();

        assertEquals(3, cookies.length);
        assertEquals("a", cookies[0].getName());
        assertEquals("b", cookies[1].getName());
        assertEquals("c", cookies[2].getName());
    }

    @Test
    void shouldReadMultipleCookieHeaders() {
        Cookie[] cookies = cookieRequest("a=1", "b=2").getCookies();

        assertEquals(2, cookies.length);
        assertEquals("a", cookies[0].getName());
        assertEquals("1", cookies[0].getValue());
        assertEquals("b", cookies[1].getName());
        assertEquals("2", cookies[1].getValue());
    }

    @Test
    void shouldKeepDuplicateCookieNamesInOrder() {
        Cookie[] cookies = cookieRequest("foo=1; foo=2").getCookies();

        assertEquals(2, cookies.length);
        assertEquals("foo", cookies[0].getName());
        assertEquals("1", cookies[0].getValue());
        assertEquals("foo", cookies[1].getName());
        assertEquals("2", cookies[1].getValue());
    }

    @Test
    void shouldPreserveCookieValuesVerbatim() {
        Cookie[] encodedCookies = cookieRequest("foo=hello%20world").getCookies();

        assertEquals(1, encodedCookies.length);
        assertEquals("hello%20world", encodedCookies[0].getValue());

        Cookie[] emptyCookies = cookieRequest("foo=").getCookies();

        assertEquals(1, emptyCookies.length);
        assertEquals("foo", emptyCookies[0].getName());
        assertEquals("", emptyCookies[0].getValue());
    }

    @Test
    void shouldReturnNullCookiesForMalformedHeaderWithoutThrowing() {
        assertNull(assertDoesNotThrow(() -> cookieRequest("=;;garbage").getCookies()));
        assertNull(assertDoesNotThrow(() -> cookieRequest("").getCookies()));
    }

    @Test
    void shouldReturnNullCookiesWhenNoCookieHeader() {
        assertNull(cookieRequest().getCookies());
    }

    @Test
    void shouldCopyCookiesDefensivelyButCacheElements() {
        NettyHttpServletRequest request = cookieRequest("a=1; b=2");

        Cookie[] first = request.getCookies();
        Cookie[] second = request.getCookies();

        /*
         * Each call hands back a fresh array (parity with getParameterValues), so a caller that
         * mutates the returned array cannot corrupt a later getCookies() in the same request.
         */
        assertNotSame(first, second);
        first[0] = null;
        assertNotNull(request.getCookies()[0]);
        // The shallow copy still shares element instances: cookies are parsed once, then cached.
        assertSame(second[1], request.getCookies()[1]);
    }

    // --- Sessions (issue #13) ---

    private static final HttpConnectionMetadata INSECURE = new HttpConnectionMetadata("", 0, "", 0, false, "");
    private static final HttpConnectionMetadata SECURE = new HttpConnectionMetadata("", 0, "", 0, true, "");

    /**
     * One request/response pair over a shared servlet context, as the dispatcher builds them.
     */
    record Exchange(NettyHttpServletRequest request, NettyHttpServletResponse response) {

        List<String> setCookies() {
            return List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        }

        String setCookie() {
            List<String> all = setCookies();
            assertEquals(1, all.size(), "Expected exactly one Set-Cookie but got " + all);
            return all.getFirst();
        }

        /**
         * The emitted cookie's Path, parsed rather than substring-matched.
         */
        String cookiePath() {
            return HttpCookie.parse(setCookie()).getFirst().getPath();
        }
    }

    /**
     * Creating a session lazily starts a sweeper thread, so every context these tests build has to be
     * closed again or the whole mvc suite carries one leaked thread per session test.
     */
    private final List<NettyServletContext> sessionContexts = new ArrayList<>();

    @AfterEach
    void closeSessionContexts() {
        sessionContexts.forEach(NettyServletContext::close);
    }

    private Exchange exchange(DefaultNettyServletContext context,
                              HttpConnectionMetadata connection,
                              String cookieHeader) {
        sessionContexts.add(context);
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x");
        if (cookieHeader != null) {
            nettyRequest.headers().set(HttpHeaderNames.COOKIE, cookieHeader);
        }
        var response = new NettyHttpServletResponse();
        return new Exchange(new NettyHttpServletRequest(nettyRequest, InputStream.nullInputStream(), connection, context, response), response);
    }

    private Exchange exchange(DefaultNettyServletContext context) {
        return exchange(context, INSECURE, null);
    }

    @Test
    void shouldReturnNullFromGetSessionFalseWhenNoCookieIsPresent() {
        var exchange = exchange(new DefaultNettyServletContext());

        assertNull(exchange.request().getSession(false));
    }

    @Test
    void shouldWriteNoSetCookieOnGetSessionFalse() {
        /*
         * DispatcherServlet calls getSession(false) on every request via SessionFlashMapManager, so this
         * is the stateless hot path: it must neither create a session nor touch the response.
         */
        var exchange = exchange(new DefaultNettyServletContext());

        exchange.request().getSession(false);

        assertTrue(exchange.setCookies().isEmpty());
    }

    @Test
    void shouldCreateSessionAndEmitCookieOnGetSessionTrue() {
        var context = new DefaultNettyServletContext();
        var exchange = exchange(context);

        var session = exchange.request().getSession(true);

        assertNotNull(session);
        assertTrue(session.isNew());
        assertEquals(1, context.getSessionManager().size());
        assertTrue(exchange.setCookie().startsWith(NettySessionCookieConfig.DEFAULT_NAME + "=" + session.getId()));
    }

    @Test
    void shouldCreateSessionOnNoArgGetSession() {
        var exchange = exchange(new DefaultNettyServletContext());

        assertNotNull(exchange.request().getSession());
    }

    @Test
    void shouldReturnSameSessionFromGetSessionTrueWithinRequest() {
        var exchange = exchange(new DefaultNettyServletContext());

        var first = exchange.request().getSession(true);
        var second = exchange.request().getSession(true);

        assertSame(first, second);
        assertEquals(1, exchange.setCookies().size(), "The cookie is emitted once, at creation");
    }

    @Test
    void shouldResolveExistingSessionFromCookieWithoutReEmitting() {
        var context = new DefaultNettyServletContext();
        var existing = context.getSessionManager().create();

        var exchange = exchange(context, INSECURE, NettySessionCookieConfig.DEFAULT_NAME + "=" + existing.getId());

        assertSame(existing, exchange.request().getSession(false));
        assertTrue(exchange.setCookies().isEmpty(),
            "The client already holds this cookie; re-sending it on every response is pure overhead");
    }

    @Test
    void shouldFindSessionCookieAmongOthers() {
        var context = new DefaultNettyServletContext();
        var existing = context.getSessionManager().create();

        var exchange = exchange(context, INSECURE, "theme=dark; JSESSIONID=" + existing.getId() + "; lang=en");

        assertSame(existing, exchange.request().getSession(false));
    }

    @Test
    void shouldYieldNoSessionButStaleRequestedIdForUnknownId() {
        var exchange = exchange(new DefaultNettyServletContext(), INSECURE, "JSESSIONID=" + UNKNOWN_SESSION_ID);

        // SessionManagementFilter keys on exactly this triple to detect an expired session.
        assertNull(exchange.request().getSession(false));
        assertEquals(UNKNOWN_SESSION_ID, exchange.request().getRequestedSessionId());
        assertFalse(exchange.request().isRequestedSessionIdValid());
        assertTrue(exchange.request().isRequestedSessionIdFromCookie());
    }

    @Test
    void shouldStillCreateFreshSessionAfterUnknownSessionId() {
        var context = new DefaultNettyServletContext();
        var exchange = exchange(context, INSECURE, "JSESSIONID=" + UNKNOWN_SESSION_ID);

        var created = exchange.request().getSession(true);

        assertNotEquals(UNKNOWN_SESSION_ID, created.getId());
        assertTrue(exchange.setCookie().startsWith(NettySessionCookieConfig.DEFAULT_NAME + "=" + created.getId()));
    }

    @Test
    void shouldNotLetStaleDuplicateSessionCookieMaskLiveSession() {
        /*
         * Issue #91, through the real cookie decoder: the stale duplicate arrives first on the wire and
         * used to win outright.
         */
        var context = new DefaultNettyServletContext();
        var existing = context.getSessionManager().create();

        var exchange = exchange(context, INSECURE,
            NettySessionCookieConfig.DEFAULT_NAME + "=" + UNKNOWN_SESSION_ID + "; "
                + NettySessionCookieConfig.DEFAULT_NAME + "=" + existing.getId());

        assertSame(existing, exchange.request().getSession(false), "the live duplicate must be the one resolved");
        assertEquals(existing.getId(), exchange.request().getRequestedSessionId());
        assertTrue(exchange.request().isRequestedSessionIdValid());
    }

    @Test
    void shouldReportLastOfAllStaleDuplicateCookiesAsRequestedId() {
        var exchange = exchange(new DefaultNettyServletContext(), INSECURE,
            NettySessionCookieConfig.DEFAULT_NAME + "=" + UNKNOWN_SESSION_ID + "; "
                + NettySessionCookieConfig.DEFAULT_NAME + "=" + OTHER_UNKNOWN_SESSION_ID);

        /*
         * The same triple SessionManagementFilter keys on as the single-cookie case: an id was presented
         * and it is not valid, which is an expired session -- not a request that carried none.
         */
        assertNull(exchange.request().getSession(false));
        assertEquals(OTHER_UNKNOWN_SESSION_ID, exchange.request().getRequestedSessionId());
        assertFalse(exchange.request().isRequestedSessionIdValid());
        assertTrue(exchange.request().isRequestedSessionIdFromCookie());
    }

    @Test
    void shouldNotTreatDifferentlyCasedNameAsSessionCookie() {
        /*
         * The security edge of RFC 6265 4.1.1: a mis-cased cookie, which anything sharing the host can
         * set, must not be read as the session id even when it names a live session and the
         * correctly-named one is dead.
         */
        var context = new DefaultNettyServletContext();
        var live = context.getSessionManager().create();

        String miscased = NettySessionCookieConfig.DEFAULT_NAME.toLowerCase(Locale.ROOT);
        var exchange = exchange(context, INSECURE,
            miscased + "=" + live.getId() + "; " + NettySessionCookieConfig.DEFAULT_NAME + "=" + UNKNOWN_SESSION_ID);

        assertNull(exchange.request().getSession(false), "the mis-cased cookie must not resolve a session");
        assertEquals(UNKNOWN_SESSION_ID, exchange.request().getRequestedSessionId());
        assertFalse(exchange.request().isRequestedSessionIdValid());
    }

    @Test
    void shouldReportEmptyRequestedSessionIdForEmptyCookieValue() {
        var exchange = exchange(new DefaultNettyServletContext(), INSECURE, "JSESSIONID=");

        assertEquals("", exchange.request().getRequestedSessionId(),
            "an empty value is an id the client presented, as Tomcat and Jetty report it, not an absent one");
        assertFalse(exchange.request().isRequestedSessionIdValid());
        assertTrue(exchange.request().isRequestedSessionIdFromCookie());
    }

    @Test
    void shouldReturnNullRequestedSessionIdWhenNoCookieIsPresent() {
        /*
         * Not "": SessionManagementFilter treats any non-null requested id as a session to validate, so
         * an empty string would make it fire its invalid-session strategy on every stateless request.
         */
        var exchange = exchange(new DefaultNettyServletContext());

        assertNull(exchange.request().getRequestedSessionId());
        assertFalse(exchange.request().isRequestedSessionIdValid());
        assertFalse(exchange.request().isRequestedSessionIdFromCookie());
    }

    @Test
    void shouldReturnNullRequestedSessionIdWithOnlyOtherCookies() {
        var exchange = exchange(new DefaultNettyServletContext(), INSECURE, "theme=dark");

        assertNull(exchange.request().getRequestedSessionId());
    }

    @Test
    void shouldReportRequestedSessionIdValidForLiveSession() {
        var context = new DefaultNettyServletContext();
        var existing = context.getSessionManager().create();

        var exchange = exchange(context, INSECURE, NettySessionCookieConfig.DEFAULT_NAME + "=" + existing.getId());

        assertTrue(exchange.request().isRequestedSessionIdValid());
        assertEquals(existing.getId(), exchange.request().getRequestedSessionId());
    }

    @Test
    void shouldAlwaysReportRequestedSessionIdNotFromUrl() {
        /*
         * URL rewriting is permanently out of scope: encodeURL is the identity, and COOKIE is the only
         * effective tracking mode.
         */
        assertFalse(exchange(new DefaultNettyServletContext()).request().isRequestedSessionIdFromURL());
    }

    @Test
    void shouldReturnNullFromGetSessionFalseAfterInvalidate() {
        var exchange = exchange(new DefaultNettyServletContext());
        exchange.request().getSession(true).invalidate();

        assertNull(exchange.request().getSession(false),
            "The memoized session must be dropped once it is invalidated");
    }

    @Test
    void shouldCreateFreshSessionAndCookieOnGetSessionAfterInvalidate() {
        var context = new DefaultNettyServletContext();
        var exchange = exchange(context);
        var first = exchange.request().getSession(true);
        String firstId = first.getId();
        first.invalidate();

        var second = exchange.request().getSession(true);

        assertNotSame(first, second);
        assertNotEquals(firstId, second.getId());
        /*
         * Replaced, not appended: leaving the first id as an earlier Set-Cookie of the same name would
         * hand any client reading that header an id already unbound from the store.
         */
        assertEquals(1, exchange.setCookies().size(), "Actual: " + exchange.setCookies());
        assertTrue(exchange.setCookie().startsWith(NettySessionCookieConfig.DEFAULT_NAME + "=" + second.getId()));
    }

    // --- Session cookie attributes ---

    @Test
    void shouldMakeSessionCookieHttpOnlyByDefault() {
        var exchange = exchange(new DefaultNettyServletContext());
        exchange.request().getSession(true);

        assertTrue(exchange.setCookie().contains("HTTPOnly"), "Actual: " + exchange.setCookie());
    }

    @Test
    void shouldGiveSessionCookieNoMaxAgeByDefault() {
        var exchange = exchange(new DefaultNettyServletContext());
        exchange.request().getSession(true);

        assertFalse(exchange.setCookie().contains("Max-Age"),
            "A browser-session cookie carries no Max-Age");
    }

    @Test
    void shouldDefaultSessionCookiePathToRootForRootContext() {
        var exchange = exchange(new DefaultNettyServletContext());
        exchange.request().getSession(true);

        /*
         * The root context path is the "" sentinel; an empty Path= attribute would be meaningless, so
         * this is the one place it must be translated to "/". Parsed rather than matched by substring:
         * "Path=/" is a prefix of every other path, so contains() would accept any of them.
         */
        assertEquals("/", exchange.cookiePath(), "Actual: " + exchange.setCookie());
    }

    @Test
    void shouldDefaultSessionCookiePathToContextPath() {
        var context = new DefaultNettyServletContext();
        context.setContextPath("/app");
        var exchange = exchange(context);
        exchange.request().getSession(true);

        assertEquals("/app", exchange.cookiePath(), "Actual: " + exchange.setCookie());
    }

    @Test
    void shouldPreferConfiguredPathOverContextPath() {
        /*
         * The configured value is deliberately not a prefix of the context path, and vice versa: with
         * "/" against "/app" the assertion would hold whichever won.
         */
        var context = new DefaultNettyServletContext();
        context.setContextPath("/app");
        context.getSessionCookieConfig().setPath("/custom");
        var exchange = exchange(context);
        exchange.request().getSession(true);

        assertEquals("/custom", exchange.cookiePath(), "Actual: " + exchange.setCookie());
    }

    @Test
    void shouldUseConfiguredNameForSessionCookie() {
        var context = new DefaultNettyServletContext();
        context.getSessionCookieConfig().setName("SID");
        var exchange = exchange(context);

        var session = exchange.request().getSession(true);

        assertTrue(exchange.setCookie().startsWith("SID=" + session.getId()));
    }

    @Test
    void shouldAlsoAcceptConfiguredCookieNameOnWayIn() {
        var context = new DefaultNettyServletContext();
        context.getSessionCookieConfig().setName("SID");
        var existing = context.getSessionManager().create();

        var exchange = exchange(context, INSECURE, "SID=" + existing.getId());

        assertSame(existing, exchange.request().getSession(false));
    }

    @Test
    void shouldCarryConfiguredAttributesOnSessionCookie() {
        var context = new DefaultNettyServletContext();
        context.getSessionCookieConfig().setDomain("example.test");
        context.getSessionCookieConfig().setMaxAge(60);
        context.getSessionCookieConfig().setAttribute("SameSite", "Lax");
        var exchange = exchange(context);

        exchange.request().getSession(true);

        String setCookie = exchange.setCookie();
        assertTrue(setCookie.contains("Domain=example.test"), "Actual: " + setCookie);
        assertTrue(setCookie.contains("Max-Age=60"), "Actual: " + setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), "Actual: " + setCookie);
    }

    @Test
    void shouldMakeSessionCookieSecureOverSecureConnection() {
        var exchange = exchange(new DefaultNettyServletContext(), SECURE, null);

        exchange.request().getSession(true);

        assertTrue(exchange.setCookie().contains("Secure"), "Actual: " + exchange.setCookie());
    }

    @Test
    void shouldNotMakeSessionCookieSecureOverPlaintextConnection() {
        var exchange = exchange(new DefaultNettyServletContext());

        exchange.request().getSession(true);

        assertFalse(exchange.setCookie().contains("Secure"), "Actual: " + exchange.setCookie());
    }

    @Test
    void shouldApplyConfiguredSecureFlagEvenOverPlaintext() {
        var context = new DefaultNettyServletContext();
        context.getSessionCookieConfig().setSecure(true);
        var exchange = exchange(context);

        exchange.request().getSession(true);

        assertTrue(exchange.setCookie().contains("Secure"), "Actual: " + exchange.setCookie());
    }

    @Test
    void shouldEmitNoCookieWhenCookieTrackingIsDisabled() {
        var context = new DefaultNettyServletContext();
        context.setSessionTrackingModes(Set.of());
        var exchange = exchange(context);

        assertNotNull(exchange.request().getSession(true), "The session still exists, it is just not tracked");
        assertTrue(exchange.setCookies().isEmpty());
    }

    // --- changeSessionId (session fixation, issue #52) ---

    @Test
    void shouldRotateIdAndReEmitCookieOnChangeSessionId() {
        var context = new DefaultNettyServletContext();
        var exchange = exchange(context);
        var session = exchange.request().getSession(true);
        String oldId = session.getId();

        String newId = exchange.request().changeSessionId();

        assertNotEquals(oldId, newId);
        assertEquals(newId, session.getId());
        assertSame(session, context.getSessionManager().find(newId));
        assertNull(context.getSessionManager().find(oldId));
        assertTrue(exchange.setCookies().getLast().startsWith(NettySessionCookieConfig.DEFAULT_NAME + "=" + newId));
    }

    @Test
    void shouldLeaveClientHoldingNewIdWhenSessionIdListenerThrows() {
        /*
         * sessionIdChanged fires after the rotation has committed and before writeSessionCookie runs, so
         * a listener that throws would unwind past the Set-Cookie: the store knows only the new id while
         * the browser still holds the old one, so every later request mints a fresh session and the user
         * is silently logged out. Tomcat's tellChangedSessionId wraps each listener and logs.
         */
        var context = new DefaultNettyServletContext();
        var exchange = exchange(context);
        var session = exchange.request().getSession(true);
        String oldId = session.getId();
        context.addListener((HttpSessionIdListener) (event, previousId) -> {
            throw new IllegalStateException("session registry is down");
        });

        String newId = assertDoesNotThrow(() -> exchange.request().changeSessionId(),
            "a bystander listener must not abort a rotation that has already committed");

        assertNotEquals(oldId, newId);
        assertTrue(exchange.setCookies().getLast().startsWith(NettySessionCookieConfig.DEFAULT_NAME + "=" + newId),
            "the client must be handed the id the store now holds; Actual: " + exchange.setCookies());
    }

    @Test
    void shouldPreserveAttributesOnChangeSessionId() {
        var exchange = exchange(new DefaultNettyServletContext());
        var session = exchange.request().getSession(true);
        session.setAttribute("user", "alice");

        exchange.request().changeSessionId();

        assertEquals("alice", session.getAttribute("user"));
    }

    @Test
    void shouldStillRotateWithoutThrowingOnChangeSessionIdAfterCommit() throws Exception {
        /*
         * changeSessionId declares IllegalStateException only for "no session"; the commit-time throw
         * belongs to getSession(create). Tomcat routes the rotated cookie through addCookie, which is
         * specified to have no effect after a commit -- so this is silent there and must be here.
         *
         * The session is seeded before the commit deliberately: "no further cookie" alone is satisfied by
         * addCookie's own guard, whereas an unguarded rotation would strip the already-emitted header as
         * it scanned for the name to replace, leaving the client with no session cookie at all.
         */
        var context = new DefaultNettyServletContext();
        var exchange = exchange(context);
        var existing = exchange.request().getSession(true);
        String originalId = existing.getId();
        exchange.response().sendRedirect("/elsewhere");

        String newId = assertDoesNotThrow(() -> exchange.request().changeSessionId());

        assertNotEquals(originalId, newId, "the rotation must still produce a new id");
        assertSame(existing, context.getSessionManager().find(newId),
            "the rotation itself must still take effect");
        assertTrue(exchange.setCookie().startsWith(NettySessionCookieConfig.DEFAULT_NAME + "=" + originalId),
            "a committed response keeps the cookie it already emitted; Actual: " + exchange.setCookies());
    }

    @Test
    void shouldThrowOnChangeSessionIdWithoutSession() {
        var exchange = exchange(new DefaultNettyServletContext());

        assertThrows(IllegalStateException.class, () -> exchange.request().changeSessionId());
    }

    @Test
    void shouldRepointRequestedIdAtNewOneOnChangeSessionId() {
        var context = new DefaultNettyServletContext();
        var existing = context.getSessionManager().create();
        var exchange = exchange(context, INSECURE, NettySessionCookieConfig.DEFAULT_NAME + "=" + existing.getId());

        String newId = exchange.request().changeSessionId();

        /*
         * Carrying the pre-rotation id would have getRequestedSessionId() name a session that no longer
         * exists, and contradict isRequestedSessionIdValid().
         */
        assertEquals(newId, exchange.request().getRequestedSessionId());
        assertTrue(exchange.request().isRequestedSessionIdValid());
    }

    @Test
    void shouldReplaceEarlierCookieNotAppendOnChangeSessionId() {
        var exchange = exchange(new DefaultNettyServletContext());
        exchange.request().getSession(true);

        String newId = exchange.request().changeSessionId();

        assertEquals(1, exchange.setCookies().size(),
            "the pre-rotation id is already unbound; leaving it as an earlier Set-Cookie of the same "
                + "name hands it to any client that reads the first one. Actual: " + exchange.setCookies());
        assertTrue(exchange.setCookie().startsWith(NettySessionCookieConfig.DEFAULT_NAME + "=" + newId));
    }

    // --- Creating a session too late (Servlet contract) ---

    @Test
    void shouldThrowWhenCreatingSessionAfterResponseIsCommitted() throws Exception {
        var exchange = exchange(new DefaultNettyServletContext());
        exchange.response().sendRedirect("/elsewhere");

        /*
         * The Servlet contract: "If the container is using cookies to maintain session integrity and is
         * asked to create a new session when the response is committed, an IllegalStateException is
         * thrown." Returning a session whose id can never reach the client would leave the application
         * silently starting a new one on every request.
         */
        assertThrows(IllegalStateException.class, () -> exchange.request().getSession(true));
    }

    @Test
    void shouldNotLeaveSessionCreatedAfterCommitInStore() throws Exception {
        var context = new DefaultNettyServletContext();
        var exchange = exchange(context);
        exchange.response().sendRedirect("/elsewhere");

        assertThrows(IllegalStateException.class, () -> exchange.request().getSession(true));

        assertEquals(0, context.getSessionManager().size(), "the refusal must precede the creation");
    }

    @Test
    void shouldStillResolveExistingSessionAfterCommit() throws Exception {
        // Only *creation* is barred: an already-tracked session needs no new cookie.
        var context = new DefaultNettyServletContext();
        var existing = context.getSessionManager().create();
        var exchange = exchange(context, INSECURE, NettySessionCookieConfig.DEFAULT_NAME + "=" + existing.getId());
        exchange.response().sendRedirect("/elsewhere");

        assertSame(existing, exchange.request().getSession(true));
    }

    @Test
    void shouldAllowCreatingSessionAfterCommitWithoutCookieTracking() throws Exception {
        /*
         * The spec conditions the throw on the container using cookies; with tracking off there is no
         * id to deliver and nothing is lost.
         */
        var context = new DefaultNettyServletContext();
        context.setSessionTrackingModes(Set.of());
        var exchange = exchange(context);
        exchange.response().sendRedirect("/elsewhere");

        assertNotNull(exchange.request().getSession(true));
    }

    // --- isRequestedSessionIdValid tracks the store, it is not latched ---

    @Test
    void shouldReportRequestedSessionIdInvalidOnceInvalidated() {
        var context = new DefaultNettyServletContext();
        var existing = context.getSessionManager().create();
        var exchange = exchange(context, INSECURE, NettySessionCookieConfig.DEFAULT_NAME + "=" + existing.getId());
        assertTrue(exchange.request().isRequestedSessionIdValid());

        exchange.request().getSession(false).invalidate();

        /*
         * The contract is whether the id is *still* valid; latching the first answer would keep Spring
         * Security's InvalidSessionStrategy from ever firing for a session killed mid-dispatch.
         */
        assertFalse(exchange.request().isRequestedSessionIdValid());
    }

    @Test
    void shouldNotRefreshSessionOnIsRequestedSessionIdValid() {
        var context = new DefaultNettyServletContext();
        var existing = context.getSessionManager().create();
        var exchange = exchange(context, INSECURE, NettySessionCookieConfig.DEFAULT_NAME + "=" + existing.getId());

        exchange.request().isRequestedSessionIdValid();

        assertTrue(existing.isNew(), "a validity query must not clear isNew or touch the access time");
    }

    // --- Request attribute listeners (issue #17) ---

    private static List<String> recordRequestAttributes(DefaultNettyServletContext context) {
        var events = new ArrayList<String>();
        context.addListener(new ServletRequestAttributeListener() {
            @Override
            public void attributeAdded(ServletRequestAttributeEvent event) {
                events.add("added:" + event.getName() + "=" + event.getValue());
            }

            @Override
            public void attributeReplaced(ServletRequestAttributeEvent event) {
                events.add("replaced:" + event.getName() + "=" + event.getValue());
            }

            @Override
            public void attributeRemoved(ServletRequestAttributeEvent event) {
                events.add("removed:" + event.getName() + "=" + event.getValue());
            }
        });
        return events;
    }

    @Test
    void shouldFireAttributeListenerOnRequestAttributeMutation() {
        var context = new DefaultNettyServletContext();
        var events = recordRequestAttributes(context);
        var request = request(new HttpConnectionMetadata("", 0, "", 0, false, ""), context);

        request.setAttribute("stage", "one");
        request.setAttribute("stage", "two");
        request.removeAttribute("stage");

        assertEquals(List.of("added:stage=one", "replaced:stage=one", "removed:stage=two"), events);
    }

    @Test
    void shouldFireRemovedWhenSettingRequestAttributeToNull() {
        var context = new DefaultNettyServletContext();
        var events = recordRequestAttributes(context);
        var request = request(new HttpConnectionMetadata("", 0, "", 0, false, ""), context);
        request.setAttribute("stage", "one");

        request.setAttribute("stage", null);

        assertEquals(List.of("added:stage=one", "removed:stage=one"), events);
    }

    @Test
    void shouldFireNothingWhenRemovingAbsentRequestAttribute() {
        var context = new DefaultNettyServletContext();
        var events = recordRequestAttributes(context);
        var request = request(new HttpConnectionMetadata("", 0, "", 0, false, ""), context);

        request.removeAttribute("never-set");
        request.setAttribute("never-set", null);

        assertTrue(events.isEmpty(), "a mutation that changes nothing notifies nothing; got " + events);
    }

    @Test
    void shouldNameRequestItHappenedOnInAttributeEvent() {
        var context = new DefaultNettyServletContext();
        var seen = new Object[2];
        context.addListener(new ServletRequestAttributeListener() {
            @Override
            public void attributeAdded(ServletRequestAttributeEvent event) {
                seen[0] = event.getServletRequest();
                seen[1] = event.getServletContext();
            }
        });
        var request = request(new HttpConnectionMetadata("", 0, "", 0, false, ""), context);

        request.setAttribute("stage", "one");

        assertSame(request, seen[0]);
        assertSame(context, seen[1]);
    }
}
