package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpResponseWriter;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void sendErrorCommitsTheResponse() throws Exception {
        var response = new NettyHttpServletResponse();

        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        assertTrue(response.isCommitted());
    }

    @Test
    void headersSetAfterSendErrorAreIgnored() throws Exception {
        var response = new NettyHttpServletResponse();
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        response.setHeader(HttpHeaderNames.ALLOW.toString(), "GET, HEAD, POST, PUT, DELETE, OPTIONS");

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertFalse(httpResponse.headers().contains(HttpHeaderNames.ALLOW));
    }

    @Test
    void statusSetAfterSendErrorIsIgnored() throws Exception {
        var response = new NettyHttpServletResponse();
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        response.setStatus(HttpResponseStatus.OK.code());

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertEquals(HttpResponseStatus.NOT_FOUND.code(), httpResponse.status().code());
    }

    @Test
    void sendRedirectCommitsTheResponseAndKeepsLocation() throws Exception {
        var response = new NettyHttpServletResponse();

        response.sendRedirect("/elsewhere", HttpResponseStatus.FOUND.code(), true);

        assertTrue(response.isCommitted());
        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertEquals(HttpResponseStatus.FOUND.code(), httpResponse.status().code());
        assertEquals("/elsewhere", httpResponse.headers().get(HttpHeaderNames.LOCATION));
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
    void addCookieAppliesTheResolverWhenTheCookieDeclaresNoSameSite() throws Exception {
        var response = new NettyHttpServletResponse(cookie -> "Strict");
        response.addCookie(new Cookie("XSRF-TOKEN", "t"));

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertTrue(httpResponse.headers().get(HttpHeaderNames.SET_COOKIE).contains("SameSite=Strict"));
    }

    @Test
    void addCookiePrefersAnExplicitSameSiteOverTheResolver() throws Exception {
        var response = new NettyHttpServletResponse(cookie -> "None");
        Cookie cookie = new Cookie("XSRF-TOKEN", "t");
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        String setCookie = httpResponse.headers().get(HttpHeaderNames.SET_COOKIE);
        assertTrue(setCookie.contains("SameSite=Lax"), "Actual: " + setCookie);
        assertFalse(setCookie.contains("SameSite=None"), "Actual: " + setCookie);
    }

    @Test
    void addCookieOmitsSameSiteWhenTheResolverDeclines() throws Exception {
        var response = new NettyHttpServletResponse(cookie -> null);
        response.addCookie(new Cookie("XSRF-TOKEN", "t"));

        FullHttpResponse httpResponse = response.toFullHttpResponse();
        assertFalse(httpResponse.headers().get(HttpHeaderNames.SET_COOKIE).contains("SameSite"));
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
        // The space is an invalid RFC 6265 cookie-octet, so ServerCookieEncoder.STRICT rejects it.
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
        assertFalse(setCookie.contains("Version"));
    }

    // --- setCookie: replaces rather than appends (issue #13) ---

    private static List<String> setCookieHeaders(NettyHttpServletResponse response) throws Exception {
        return response.toFullHttpResponse().headers().getAll(HttpHeaderNames.SET_COOKIE);
    }

    @Test
    void setCookieReplacesAnEarlierCookieOfTheSameName() throws Exception {
        var response = new NettyHttpServletResponse();
        response.setCookie(new Cookie("sid", "first"));

        response.setCookie(new Cookie("sid", "second"));

        List<String> headers = setCookieHeaders(response);
        assertEquals(1, headers.size(), "Actual: " + headers);
        assertTrue(headers.getFirst().startsWith("sid=second"), "Actual: " + headers);
    }

    @Test
    void setCookieLeavesCookiesOfOtherNamesAlone() throws Exception {
        var response = new NettyHttpServletResponse();
        response.addCookie(new Cookie("theme", "dark"));

        response.setCookie(new Cookie("sid", "xyz"));

        List<String> headers = setCookieHeaders(response);
        assertEquals(2, headers.size(), "Actual: " + headers);
        assertTrue(headers.stream().anyMatch(h -> h.startsWith("theme=dark")), "Actual: " + headers);
    }

    @Test
    void setCookieDoesNotDropACookieWhoseNameMerelyStartsWithTheSameText() throws Exception {
        // The scan matches on "name=", so a longer name sharing a prefix must survive.
        var response = new NettyHttpServletResponse();
        response.addCookie(new Cookie("SIDE", "kept"));

        response.setCookie(new Cookie("SID", "xyz"));

        List<String> headers = setCookieHeaders(response);
        assertEquals(2, headers.size(), "Actual: " + headers);
        assertTrue(headers.stream().anyMatch(h -> h.startsWith("SIDE=kept")), "Actual: " + headers);
    }

    @Test
    void setCookieWithNoPriorHeaderJustAdds() throws Exception {
        var response = new NettyHttpServletResponse();

        response.setCookie(new Cookie("sid", "xyz"));

        assertEquals(1, setCookieHeaders(response).size());
    }

    @Test
    void setCookieIsIgnoredOnceCommitted() throws Exception {
        // The Servlet contract says a cookie written after the commit has no effect. A cookie is seeded
        // before the commit deliberately: without one, "no Set-Cookie appeared" is satisfied by
        // addCookie's own guard, which setCookie delegates to. What is unique to setCookie is the replace
        // scan running *before* that delegation -- unguarded, a post-commit write strips an
        // already-emitted header off a response whose content is by definition already decided.
        var response = new NettyHttpServletResponse();
        response.addCookie(new Cookie("sid", "first"));

        response.sendRedirect("/elsewhere");
        response.setCookie(new Cookie("sid", "second"));

        List<String> headers = setCookieHeaders(response);
        assertEquals(1, headers.size(), "Actual: " + headers);
        assertTrue(headers.getFirst().startsWith("sid=first"), "Actual: " + headers.getFirst());
    }

    // --- streaming: the buffer, the commit, and what the wire sees (issue #37) ---

    @Test
    void completeWritesASingleFullResponseWhenNothingWasFlushed() throws Exception {
        var wire = new RecordingWriter();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, wire);
        response.getOutputStream().write("hi".getBytes(StandardCharsets.UTF_8));

        response.complete();

        FullHttpResponse only = assertInstanceOf(FullHttpResponse.class, wire.single());
        assertEquals("hi", only.content().toString(StandardCharsets.UTF_8));
        assertEquals(2, HttpUtil.getContentLength(only, -1L),
            "a response that fits the buffer keeps its declared length rather than becoming chunked");
    }

    @Test
    void outputStreamFlushesToTheWireOnceTheBufferIsFull() throws Exception {
        var wire = new RecordingWriter();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, wire);
        response.setBufferSize(8);

        response.getOutputStream().write("0123456789".getBytes(StandardCharsets.UTF_8));

        assertInstanceOf(HttpResponse.class, wire.parts.get(0));
        assertFalse(wire.parts.get(0) instanceof FullHttpResponse,
            "an overflowing body is not complete, so it cannot go out as one full response");
        assertEquals("0123456789", contentOf(wire.parts.get(1)));
    }

    /**
     * The other half of {@link #outputStreamFlushesToTheWireOnceTheBufferIsFull}: most handlers write
     * text, and a writer that wrote past the container's buffer straight into it would keep the whole
     * body in heap however large it grew.
     */
    @Test
    void writerFlushesToTheWireOnceTheBufferIsFull() throws Exception {
        var wire = new RecordingWriter();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, wire);
        response.setBufferSize(8);

        response.getWriter().write("0123456789");
        response.getWriter().flush();

        assertInstanceOf(HttpResponse.class, wire.parts.get(0));
        assertEquals("0123456789", contentOf(wire.parts.get(1)));
    }

    @Test
    void getBufferSizeReportsTheConfiguredSize() {
        var response = new NettyHttpServletResponse();
        response.setBufferSize(4096);

        assertEquals(4096, response.getBufferSize());
    }

    @Test
    void setBufferSizeThrowsOnceContentHasBeenWritten() throws Exception {
        var response = new NettyHttpServletResponse();
        response.getOutputStream().write('x');

        assertThrows(IllegalStateException.class, () -> response.setBufferSize(4096));
    }

    /**
     * The writer holds its own encoder buffer, so flushing it after the wire flush rather than before
     * would commit an empty chunk and leave the text behind — the whole of incremental delivery for
     * anything written through {@code getWriter()}.
     */
    @Test
    void flushBufferFlushesTheCachedWriterBeforeCommitting() throws Exception {
        var wire = new RecordingWriter();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, wire);
        response.getWriter().print("event one");

        response.flushBuffer();

        assertInstanceOf(HttpResponse.class, wire.parts.get(0));
        assertEquals("event one", contentOf(wire.parts.get(1)));
    }

    @Test
    void isCommittedReportsTrueOnceTheHeadIsOnTheWire() throws Exception {
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, new RecordingWriter());
        assertFalse(response.isCommitted());

        response.flushBuffer();

        assertTrue(response.isCommitted());
    }

    @Test
    void setHeaderIsIgnoredOnceTheHeadIsOnTheWire() throws Exception {
        var wire = new RecordingWriter();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, wire);
        response.flushBuffer();

        response.setHeader("X-Late", "too late");

        assertFalse(((HttpResponse) wire.parts.get(0)).headers().contains("X-Late"));
    }

    @Test
    void resetBufferThrowsOnceTheHeadIsOnTheWire() throws Exception {
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, new RecordingWriter());
        response.flushBuffer();

        assertThrows(IllegalStateException.class, response::resetBuffer);
    }

    @Test
    void sendErrorThrowsOnceTheHeadIsOnTheWire() throws Exception {
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, new RecordingWriter());
        response.flushBuffer();

        assertThrows(IllegalStateException.class,
            () -> response.sendError(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()));
    }

    @Test
    void sendRedirectThrowsOnceTheHeadIsOnTheWire() throws Exception {
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, new RecordingWriter());
        response.flushBuffer();

        assertThrows(IllegalStateException.class,
            () -> response.sendRedirect("/elsewhere", HttpResponseStatus.FOUND.code(), true));
    }

    /**
     * The guard is on bytes actually sent, not on the spec-level commit {@code sendError} sets: nothing
     * has left, so taking the response back is honest here and must stay allowed.
     */
    @Test
    void sendErrorThenResetStillWorksWhenNothingWasFlushed() throws Exception {
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, new RecordingWriter());
        response.sendError(HttpResponseStatus.FORBIDDEN.code());

        assertDoesNotThrow(response::reset);
        assertEquals(HttpResponseStatus.OK.code(), response.getStatus());
    }

    /** Pins the no-aliasing rule that {@code takeBufferedBody()} rests on. */
    @Test
    void consecutiveChunksDoNotShareABuffer() throws Exception {
        var wire = new RecordingWriter();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, wire);
        response.getOutputStream().write("first".getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();

        response.getOutputStream().write("second".getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();

        assertEquals("first", contentOf(wire.parts.get(1)));
        assertEquals("second", contentOf(wire.parts.get(2)));
    }

    @Test
    void completeFlushesTheRemainderAndTerminatesTheStream() throws Exception {
        var wire = new RecordingWriter();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, wire);
        response.getOutputStream().write("head".getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
        response.getOutputStream().write("tail".getBytes(StandardCharsets.UTF_8));

        response.complete();

        assertEquals(3, wire.parts.size(), "the remainder rides on the terminator; got " + wire.parts);
        assertInstanceOf(LastHttpContent.class, wire.parts.get(2),
            "a streamed response must be terminated so the exchange can be counted out");
        assertEquals("tail", contentOf(wire.parts.get(2)));
    }

    private static String contentOf(HttpObject part) {
        return ((HttpContent) part).content().toString(StandardCharsets.UTF_8);
    }

    /** Stands in for the connection, so the parts a response emits can be asserted on directly. */
    private static final class RecordingWriter implements HttpResponseWriter {

        private final List<HttpObject> parts = new ArrayList<>();

        @Override
        public void write(HttpObject part) {
            parts.add(part);
        }

        HttpObject single() {
            assertEquals(1, parts.size(), "expected exactly one part, got " + parts);
            return parts.get(0);
        }
    }
}
