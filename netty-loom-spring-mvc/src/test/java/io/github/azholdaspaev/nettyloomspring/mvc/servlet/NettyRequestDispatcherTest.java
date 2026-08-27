package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyRequestDispatcherTest {

    private static final HttpConnectionMetadata CONNECTION =
        new HttpConnectionMetadata("198.51.100.2", 1234, "198.51.100.9", 8080, false);

    private DefaultNettyServletContext context;
    private NettyDispatchFactory factory;
    private List<HttpServletRequest> reached;
    private List<String> trace;

    @BeforeEach
    void setUp() {
        context = new DefaultNettyServletContext();
        reached = new ArrayList<>();
        trace = new ArrayList<>();
        terminalIs((request, response) -> {
        });
    }

    /** Installs the terminal a dispatch ends in, and the factory built over it. */
    private void terminalIs(FilterChain terminal) {
        factory = new NettyDispatchFactory(context, terminal);
        context.setDispatchFactory(factory);
    }

    /** Registers a pass-through filter that records that it ran. */
    private void registerFilter(String name, String pattern, EnumSet<DispatcherType> dispatcherTypes) {
        Filter filter = (request, response, chain) -> {
            trace.add(name);
            chain.doFilter(request, response);
        };
        context.addFilter(name, filter).addMappingForUrlPatterns(dispatcherTypes, false, pattern);
    }

    /** Records every request the terminal chain is handed, so a dispatch can be asserted on. */
    private void recordTerminal() {
        terminalIs((request, response) -> reached.add((HttpServletRequest) request));
    }

    private NettyHttpServletRequest requestFor(String uri, NettyHttpServletResponse response) {
        return new NettyHttpServletRequest(
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri),
            CONNECTION, context, response);
    }

    /** Forwards {@code uri} to {@code targetPath} and returns the request the terminal chain saw. */
    private HttpServletRequest forward(String uri, String targetPath, String queryString) throws Exception {
        recordTerminal();
        var response = new NettyHttpServletResponse();
        var request = requestFor(uri, response);

        new NettyRequestDispatcher(factory, targetPath, queryString).forward(request, response);

        return reached.getFirst();
    }

    /** Resolves {@code path} from a request for {@code uri}, forwards, and returns what the target saw. */
    private HttpServletRequest forwardVia(String uri, String path) throws Exception {
        recordTerminal();
        var response = new NettyHttpServletResponse();
        var request = requestFor(uri, response);

        request.getRequestDispatcher(path).forward(request, response);

        return reached.getFirst();
    }

    @Test
    void forwardRunsTheContextsTerminalChain() throws Exception {
        recordTerminal();
        var response = new NettyHttpServletResponse();
        var request = requestFor("/source", response);

        new NettyRequestDispatcher(factory, "/target", null).forward(request, response);

        assertEquals(1, reached.size(), "a forward runs the terminal chain exactly once; got " + reached);
    }

    // --- The target's view of the request ---

    @Test
    void theTargetSeesTheForwardedPathElements() throws Exception {
        context.setContextPath("/app");

        var target = forward("/app/src", "/t", null);

        assertEquals("/app/t", target.getRequestURI());
        assertEquals("/t", target.getServletPath());
        assertEquals("/app", target.getContextPath());
        assertNull(target.getPathInfo());
    }

    @Test
    void theForwardedRequestUriExcludesTheQuery() throws Exception {
        context.setContextPath("/app");

        var target = forward("/app/src", "/t", "q=1");

        assertEquals("/app/t", target.getRequestURI(), "the request URI is a path; the query is not part of it");
    }

    @Test
    void theTargetDispatchTypeIsForward() throws Exception {
        var target = forward("/src", "/t", null);

        assertEquals(DispatcherType.FORWARD, target.getDispatcherType());
    }

    @Test
    void theOriginalRequestStillReportsRequest() throws Exception {
        recordTerminal();
        var response = new NettyHttpServletResponse();
        var request = requestFor("/src", response);

        new NettyRequestDispatcher(factory, "/t", null).forward(request, response);

        assertEquals(DispatcherType.REQUEST, request.getDispatcherType(),
            "a forward wraps the request; it must not re-path or re-type the original");
        assertEquals("/src", request.getRequestURI());
    }

    // --- The jakarta.servlet.forward.* attributes ---

    @Test
    void theForwardAttributesCarryTheOriginalPathElements() throws Exception {
        context.setContextPath("/app");

        var target = forward("/app/src?a=1", "/t", null);

        assertEquals("/app/src", target.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI));
        assertEquals("/app", target.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH));
        assertEquals("/src", target.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH));
        assertEquals("a=1", target.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING));
    }

    @Test
    void theAttributesForANullValuedPathElementAreAbsent() throws Exception {
        var target = forward("/src", "/t", null);

        assertNull(target.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING),
            "the original carried no query, and the spec sets no attribute for a null value");
        assertNull(target.getAttribute(RequestDispatcher.FORWARD_PATH_INFO),
            "path info is always null here (#115), so its forward attribute is never set");
    }

    @Test
    void theForwardAttributeNamesAreEnumerated() throws Exception {
        context.setContextPath("/app");

        var names = Collections.list(forward("/app/src?a=1", "/t", null).getAttributeNames());

        assertTrue(names.contains(RequestDispatcher.FORWARD_REQUEST_URI), "got " + names);
        assertTrue(names.contains(RequestDispatcher.FORWARD_CONTEXT_PATH), "got " + names);
        assertTrue(names.contains(RequestDispatcher.FORWARD_SERVLET_PATH), "got " + names);
        assertTrue(names.contains(RequestDispatcher.FORWARD_QUERY_STRING), "got " + names);
    }

    @Test
    void aNestedForwardKeepsTheOutermostOriginalValues() throws Exception {
        context.setContextPath("/app");
        terminalIs((inner, res) -> {
            var current = (HttpServletRequest) inner;
            if ("/c".equals(current.getServletPath())) {
                reached.add(current);
            } else {
                new NettyRequestDispatcher(factory, "/c", null).forward(current, res);
            }
        });
        var response = new NettyHttpServletResponse();
        var request = requestFor("/app/a", response);

        new NettyRequestDispatcher(factory, "/b", null).forward(request, response);

        assertEquals("/app/a", reached.getFirst().getAttribute(RequestDispatcher.FORWARD_REQUEST_URI),
            "a nested forward reports the request the client sent, not the previous hop");
        assertEquals("/a", reached.getFirst().getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH));
    }

    @Test
    void attributesSetDuringTheForwardAreVisibleToTheForwarder() throws Exception {
        terminalIs((inner, res) -> inner.setAttribute("marker", "set-by-target"));
        var response = new NettyHttpServletResponse();
        var request = requestFor("/src", response);

        new NettyRequestDispatcher(factory, "/t", null).forward(request, response);

        assertEquals("set-by-target", request.getAttribute("marker"),
            "the wrapper shares the original's attribute map, which OncePerRequestFilter's "
                + "already-filtered guard depends on to not re-run Boot's own filters on a forward");
    }

    // --- Query string and parameters (Servlet 6.1 section 9.1.1) ---

    @Test
    void theTargetsQueryStringReplacesTheOriginals() throws Exception {
        var target = forward("/src?a=1", "/t", "a=2&b=3");

        assertEquals("a=2&b=3", target.getQueryString());
    }

    @Test
    void withoutAQueryOnTheDispatchPathTheOriginalsQueryStringRemains() throws Exception {
        var target = forward("/src?a=1", "/t", null);

        assertEquals("a=1", target.getQueryString());
    }

    @Test
    void theTargetsParametersTakePrecedenceAndTheOriginalsRemain() throws Exception {
        var target = forward("/src?a=1", "/t", "a=2&b=3");

        assertEquals("2", target.getParameter("a"), "the target's value comes first");
        assertArrayEquals(new String[] {"2", "1"}, target.getParameterValues("a"),
            "the target's parameters are added to the original's, not substituted for them");
        assertEquals("3", target.getParameter("b"));
        assertArrayEquals(new String[] {"2", "1"}, target.getParameterMap().get("a"));
        assertTrue(Collections.list(target.getParameterNames()).containsAll(List.of("a", "b")));
    }

    @Test
    void withoutAQueryOnTheDispatchPathTheOriginalParametersAreUnchanged() throws Exception {
        var target = forward("/src?a=1", "/t", null);

        assertEquals("1", target.getParameter("a"));
        assertNull(target.getParameter("b"));
    }

    @Test
    void theExtraParametersAreNotVisibleAfterTheForwardReturns() throws Exception {
        recordTerminal();
        var response = new NettyHttpServletResponse();
        var request = requestFor("/src?a=1", response);

        new NettyRequestDispatcher(factory, "/t", "a=2&b=3").forward(request, response);

        assertEquals("1", request.getParameter("a"), "the target's parameters live only for the forward");
        assertNull(request.getParameter("b"));
    }

    // --- Filter matching on a forward dispatch ---

    @Test
    void aFilterMappedToForwardRunsForTheTargetPath() throws Exception {
        registerFilter("onForward", "/t", EnumSet.of(DispatcherType.FORWARD));

        forward("/src", "/t", null);

        assertEquals(List.of("onForward"), trace);
    }

    @Test
    void aRequestOnlyFilterDoesNotRerunOnForward() throws Exception {
        registerFilter("onRequest", "/t", EnumSet.of(DispatcherType.REQUEST));

        forward("/src", "/t", null);

        assertEquals(List.of(), trace, "a REQUEST-only mapping must not match a FORWARD dispatch");
    }

    @Test
    void aForwardFilterMappedToTheSourcePathDoesNotRun() throws Exception {
        registerFilter("onSource", "/src", EnumSet.of(DispatcherType.FORWARD));

        forward("/src", "/t", null);

        assertEquals(List.of(), trace, "a forward matches filters against the target path, not the source");
    }

    // --- Commit rejection and buffer reset ---

    @Test
    void forwardAfterTheHeadIsWrittenThrowsIllegalStateException() throws Exception {
        recordTerminal();
        List<HttpObject> parts = new ArrayList<>();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, parts::add);
        var request = requestFor("/src", response);
        response.flushBuffer();

        var dispatcher = new NettyRequestDispatcher(factory, "/t", null);

        assertThrows(IllegalStateException.class, () -> dispatcher.forward(request, response));
    }

    @Test
    void forwardAfterSendErrorThrowsIllegalStateException() throws Exception {
        recordTerminal();
        var response = new NettyHttpServletResponse();
        var request = requestFor("/src", response);
        response.sendError(404);

        var dispatcher = new NettyRequestDispatcher(factory, "/t", null);

        assertThrows(IllegalStateException.class, () -> dispatcher.forward(request, response),
            "sendError commits without writing the head, so guarding on resetBuffer alone lets this through");
    }

    @Test
    void theTargetIsNotRunWhenTheForwardIsRejected() throws Exception {
        recordTerminal();
        var response = new NettyHttpServletResponse();
        var request = requestFor("/src", response);
        response.sendError(404);
        var dispatcher = new NettyRequestDispatcher(factory, "/t", null);

        assertThrows(IllegalStateException.class, () -> dispatcher.forward(request, response));

        assertEquals(List.of(), reached, "a rejected forward must not reach the target");
    }

    @Test
    void anUncommittedBufferIsClearedBeforeTheTargetRuns() throws Exception {
        terminalIs((request, res) ->
            res.getOutputStream().write("after".getBytes(StandardCharsets.UTF_8)));
        List<HttpObject> parts = new ArrayList<>();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, parts::add);
        var request = requestFor("/src", response);
        response.getOutputStream().write("before".getBytes(StandardCharsets.UTF_8));

        new NettyRequestDispatcher(factory, "/t", null).forward(request, response);
        response.complete();

        assertEquals(1, parts.size(), "the forward must not complete the response; got " + parts);
        assertEquals("after", ((HttpContent) parts.getFirst()).content().toString(StandardCharsets.UTF_8),
            "output written before the forward is discarded, not prepended to the target's");
    }

    @Test
    void includeIsNotSupported() {
        var response = new NettyHttpServletResponse();
        var request = requestFor("/src", response);
        var dispatcher = new NettyRequestDispatcher(factory, "/t", null);

        assertThrows(UnsupportedOperationException.class, () -> dispatcher.include(request, response));
    }

    // --- Resolving a dispatcher path ---

    @Test
    void aContextAbsolutePathResolvesAgainstTheContext() throws Exception {
        context.setContextPath("/app");

        assertEquals("/app/other", forwardVia("/app/a/b", "/other").getRequestURI());
    }

    @Test
    void aRelativePathResolvesAgainstTheRequestDirectory() throws Exception {
        context.setContextPath("/app");

        assertEquals("/app/a/sibling", forwardVia("/app/a/b", "sibling").getRequestURI());
    }

    @Test
    void aRelativePathAtTheContextRootResolvesFromTheRoot() throws Exception {
        context.setContextPath("/app");

        assertEquals("/app/index.html", forwardVia("/app", "index.html").getRequestURI(),
            "the servlet path of a request for the bare context is empty, and its directory is the root");
    }

    @Test
    void dotDotIsNormalisedWithinTheContext() throws Exception {
        context.setContextPath("/app");

        assertEquals("/app/a/x", forwardVia("/app/a/b/c", "../x").getRequestURI());
    }

    @Test
    void theDispatchPathsQueryStringIsKept() throws Exception {
        var target = forwardVia("/src", "/t?x=1");

        assertEquals("/t", target.getRequestURI());
        assertEquals("x=1", target.getQueryString());
    }

    @Test
    void aPathThatEscapesTheContextResolvesToNoDispatcher() {
        context.setContextPath("/app");
        var request = requestFor("/app/a", new NettyHttpServletResponse());

        assertNull(request.getRequestDispatcher("../../outside"));
    }

    @Test
    void aPathParameterHidingADotSegmentResolvesToNoDispatcher() {
        context.setContextPath("/app");
        var request = requestFor("/app/a", new NettyHttpServletResponse());

        assertNull(request.getRequestDispatcher("/..;/outside"),
            "a ';' parameter must not hide a dot segment from the escape guard");
    }

    @Test
    void aPercentEncodedDotSegmentResolvesToNoDispatcher() {
        context.setContextPath("/app");
        var request = requestFor("/app/a", new NettyHttpServletResponse());

        assertNull(request.getRequestDispatcher("/%2e%2e/outside"),
            "a percent-encoded dot segment must not hide from the escape guard");
    }

    @Test
    void aMalformedEscapeResolvesToNoDispatcher() {
        var request = requestFor("/src", new NettyHttpServletResponse());

        assertNull(request.getRequestDispatcher("/a%zz"),
            "a path that cannot be decoded cannot be shown not to escape");
    }

    @Test
    void aPercentEncodedPathWithinTheContextStillResolves() {
        var request = requestFor("/src", new NettyHttpServletResponse());

        assertNotNull(request.getRequestDispatcher("/a%20b"),
            "decoding is for the escape decision only; an encoded path inside the context resolves");
    }

    @Test
    void aNullPathResolvesToNoDispatcher() {
        var request = requestFor("/src", new NettyHttpServletResponse());

        assertNull(request.getRequestDispatcher(null));
    }

    @Test
    void aValidPathResolvesToADispatcher() {
        var request = requestFor("/src", new NettyHttpServletResponse());

        assertNotNull(request.getRequestDispatcher("/t"));
    }

    @Test
    void aForwardTargetResolvesRelativePathsAgainstTheForwardedUri() throws Exception {
        context.setContextPath("/app");
        terminalIs((inner, res) -> {
            var current = (HttpServletRequest) inner;
            if ("/x/z".equals(current.getServletPath())) {
                reached.add(current);
            } else {
                current.getRequestDispatcher("z").forward(current, res);
            }
        });
        var response = new NettyHttpServletResponse();
        var request = requestFor("/app/src", response);

        new NettyRequestDispatcher(factory, "/x/y", null).forward(request, response);

        assertEquals("/app/x/z", reached.getFirst().getRequestURI(),
            "a relative path inside a forward resolves against the forwarded URI, not the original");
    }

    @Test
    void aContentLengthSetBeforeTheForwardDoesNotSurviveIt() throws Exception {
        terminalIs((request, res) ->
            res.getOutputStream().write("target".getBytes(StandardCharsets.UTF_8)));
        var response = new NettyHttpServletResponse();
        var request = requestFor("/src", response);
        response.setContentLength(9);
        response.getOutputStream().write("discarded".getBytes(StandardCharsets.UTF_8));

        new NettyRequestDispatcher(factory, "/t", null).forward(request, response);

        var httpResponse = response.toFullHttpResponse();
        assertEquals(6, httpResponse.content().readableBytes());
        assertEquals("6", httpResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH),
            "the forward cleared the body, so the length describing it must go too");
    }
}
