package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyErrorPageDispatcherTest {

    private static final HttpConnectionMetadata CONNECTION =
        new HttpConnectionMetadata("198.51.100.2", 1234, "198.51.100.9", 8080, false);

    private DefaultNettyServletContext context;
    private NettyErrorPageDispatcher errorPages;
    private List<HttpServletRequest> reached;
    private List<String> trace;

    @BeforeEach
    void setUp() {
        context = new DefaultNettyServletContext();
        reached = new ArrayList<>();
        trace = new ArrayList<>();
        terminalIs((request, response) -> reached.add((HttpServletRequest) request));
        errorPages = new NettyErrorPageDispatcher(context);
    }

    private void terminalIs(FilterChain terminal) {
        context.setDispatchFactory(new NettyDispatchFactory(context, terminal));
    }

    private void pageIs(String path) {
        context.setErrorPageResolver((status, failure, rootCause) -> path);
    }

    private void registerFilter(String name, EnumSet<DispatcherType> dispatcherTypes) {
        Filter filter = (request, response, chain) -> {
            trace.add(name);
            chain.doFilter(request, response);
        };
        context.addFilter(name, filter).addMappingForUrlPatterns(dispatcherTypes, false, "/*");
    }

    private NettyHttpServletRequest requestFor(String uri, NettyHttpServletResponse response) {
        return new NettyHttpServletRequest(
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri),
            CONNECTION, context, response);
    }

    @Test
    void noRegisteredPageMeansNothingIsDispatched() throws Exception {
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        assertFalse(errorPages.report(request, response, null));
        assertTrue(reached.isEmpty(), "with no page registered the bare status is the whole answer");
        assertTrue(response.isCommitted(), "declining must leave the errored response exactly as it was");
    }

    @Test
    void aResponseThatNeverErroredIsNotDispatched() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/fine", response);

        assertFalse(errorPages.report(request, response, null));
        assertTrue(reached.isEmpty());
    }

    @Test
    void aResolvedPageRunsWithDispatcherTypeError() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        assertTrue(errorPages.report(request, response, null));

        assertEquals(1, reached.size());
        assertEquals("/error", reached.getFirst().getServletPath());
        assertEquals(DispatcherType.ERROR, reached.getFirst().getDispatcherType());
    }

    @Test
    void theErrorPageSeesTheStatusMethodUriAndQueryAttributes() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom?q=1", response);
        response.sendError(HttpResponseStatus.NOT_FOUND.code(), "nothing here");

        errorPages.report(request, response, null);

        HttpServletRequest page = reached.getFirst();
        assertEquals(404, page.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        assertEquals("nothing here", page.getAttribute(RequestDispatcher.ERROR_MESSAGE));
        assertEquals("/boom", page.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        assertEquals("GET", page.getAttribute(RequestDispatcher.ERROR_METHOD));
        assertEquals("q=1", page.getAttribute(RequestDispatcher.ERROR_QUERY_STRING));
    }

    @Test
    void aNullMessageReachesTheErrorPageAsAnEmptyString() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        errorPages.report(request, response, null);

        assertEquals("", reached.getFirst().getAttribute(RequestDispatcher.ERROR_MESSAGE),
            "setAttribute(name, null) removes the attribute, so a null message would leave it absent");
    }

    @Test
    void theErrorPageDoesNotSeeTheForwardAttributes() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        errorPages.report(request, response, null);

        assertNull(reached.getFirst().getAttribute(RequestDispatcher.FORWARD_REQUEST_URI));
    }

    @Test
    void theRootCauseOfAServletExceptionIsWhatTheErrorPageIsToldAbout() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        var rootCause = new IllegalStateException("the real failure");

        errorPages.report(request, response, new ServletException(rootCause));

        HttpServletRequest page = reached.getFirst();
        assertEquals(rootCause, page.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        assertEquals(IllegalStateException.class, page.getAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE));
        assertEquals("the real failure", page.getAttribute(RequestDispatcher.ERROR_MESSAGE));
    }

    @Test
    void aFailureOnAnOkResponseIsReportedAsFiveHundred() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);

        errorPages.report(request, response, new IllegalStateException("bang"));

        assertEquals(500, response.getStatus());
        assertEquals(500, reached.getFirst().getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
    }

    @Test
    void aFailureOverridesTheStatusTheHandlerAlreadySet() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        response.setStatus(HttpResponseStatus.CONFLICT.code());

        errorPages.report(request, response, new IllegalStateException("bang"));

        assertEquals(500, response.getStatus(),
            "an uncaught failure is what the status describes, as Tomcat's StandardWrapperValve.exception has it");
    }

    @Test
    void aFailureIsReportedWithTheStatusItsTypeMeans() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);

        errorPages.report(request, response, new IllegalArgumentException("bad"));

        assertEquals(400, response.getStatus());
        assertEquals(400, reached.getFirst().getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
    }

    @Test
    void anUnsupportedOperationIsReportedAsNotImplemented() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);

        errorPages.report(request, response, new UnsupportedOperationException("nope"));

        assertEquals(501, response.getStatus());
        assertEquals(501, reached.getFirst().getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
    }

    @Test
    void aWrappedFailureIsReportedAsFiveHundredWhileItsRootCauseReachesThePage() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        var rootCause = new IllegalArgumentException("bad");

        errorPages.report(request, response, new ServletException("Request processing failed", rootCause));

        assertEquals(500, response.getStatus(),
            "the wrapper is the failure the container saw; only a filter throws one of these unwrapped");
        assertEquals(rootCause, reached.getFirst().getAttribute(RequestDispatcher.ERROR_EXCEPTION));
    }

    @Test
    void theBodyWrittenBeforeTheFailureIsDiscardedButTheHeadersSurvive() throws Exception {
        pageIs("/error");
        terminalIs((request, response) -> {
            try {
                response.getOutputStream().write("the error page".getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        errorPages = new NettyErrorPageDispatcher(context);
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        response.setHeader("X-Trace", "kept");
        response.getOutputStream().write("half a page".getBytes(StandardCharsets.UTF_8));
        response.sendError(HttpResponseStatus.INTERNAL_SERVER_ERROR.code());

        errorPages.report(request, response, null);

        var httpResponse = response.toFullHttpResponse();
        assertEquals("the error page", httpResponse.content().toString(StandardCharsets.UTF_8));
        assertEquals("kept", httpResponse.headers().get("X-Trace"));
    }

    @Test
    void aPageThatCanonicalisesOutOfTheContextIsNotDispatched() throws Exception {
        pageIs("/../escape");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        assertFalse(errorPages.report(request, response, null));
        assertTrue(reached.isEmpty());
    }

    @Test
    void aResponseWhoseHeadIsOnTheWireIsNotDispatched() throws Exception {
        pageIs("/error");
        List<HttpObject> written = new ArrayList<>();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, written::add);
        var request = requestFor("/boom", response);
        response.setBufferSize(1);
        response.getOutputStream().write("streamed".getBytes(StandardCharsets.UTF_8));

        assertFalse(errorPages.report(request, response, new IllegalStateException("bang")),
            "the client is already reading the response; there is nothing left to replace it with");
        assertTrue(reached.isEmpty());
    }

    @Test
    void filtersMappedToTheErrorDispatchRunAndRequestOnlyFiltersDoNot() throws Exception {
        pageIs("/error");
        registerFilter("error-filter", EnumSet.of(DispatcherType.ERROR));
        registerFilter("request-filter", EnumSet.of(DispatcherType.REQUEST));
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        errorPages.report(request, response, null);

        assertEquals(List.of("error-filter"), trace);
    }
}
