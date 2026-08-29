package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyErrorPageDispatcherTest extends DispatchFixture {

    private NettyErrorPageDispatcher errorPages;

    @BeforeEach
    void setUp() {
        recordTerminal();
        errorPages = new NettyErrorPageDispatcher(context);
    }

    private void pageIs(String path) {
        context.setErrorPageResolver((status, failure, rootCause) -> path);
    }

    private String reportOnAStreamedResponse(Throwable failure) throws Exception {
        pageIs("/error");
        List<HttpObject> written = new ArrayList<>();
        var response = new NettyHttpServletResponse(NettyCookieSameSiteResolver.NO_OPINION, written::add);
        var request = requestFor("/download", response);
        response.setBufferSize(1);
        response.getOutputStream().write("streamed".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream standardError = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertFalse(errorPages.report(request, response, failure));
        } finally {
            System.setErr(standardError);
        }
        return captured.toString(StandardCharsets.UTF_8);
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
    void theErrorPageIsDispatchedAsAGetCarryingTheOriginalMethod() throws Exception {
        pageIs("/error");
        var response = new NettyHttpServletResponse();
        var request = requestFor(HttpMethod.POST, "/boom", response);
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        errorPages.report(request, response, null);

        HttpServletRequest page = reached.getFirst();
        assertEquals("GET", page.getMethod(), "an error page mapped to GET has to be reachable from a failed POST");
        assertEquals("POST", page.getAttribute(RequestDispatcher.ERROR_METHOD));
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
    void thePageIsResolvedFromTheFailureAsThrownBeforeItsRootCause() throws Exception {
        context.setErrorPageResolver((status, failure, rootCause) ->
            failure instanceof ServletException ? "/wrapper" : "/root");
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);

        errorPages.report(request, response, new ServletException(new IllegalStateException("bang")));

        assertEquals("/wrapper", reached.getFirst().getServletPath(),
            "the outer throwable is the first candidate, so it has to arrive in the failure position");
    }

    @Test
    void aPageRegisteredForTheRootCauseAloneIsFoundUnderItsWrapper() throws Exception {
        context.setErrorPageResolver((status, failure, rootCause) ->
            rootCause instanceof IllegalStateException ? "/root" : null);
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);

        errorPages.report(request, response, new ServletException(new IllegalStateException("bang")));

        assertEquals("/root", reached.getFirst().getServletPath(),
            "the root cause reaches the resolver unwrapped, or a page registered for it never matches");
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
    void aClientThatLeftMidResponseIsNotWarnedAbout() throws Exception {
        String logged = reportOnAStreamedResponse(new ClosedChannelException());

        assertFalse(logged.contains("WARN"),
            "a client that hung up mid-download is not a fault this server owns; log was: " + logged);
    }

    @Test
    void aServerFaultMidResponseIsStillWarnedAbout() throws Exception {
        String logged = reportOnAStreamedResponse(new IllegalStateException("bang"));

        assertTrue(logged.contains("WARN"),
            "a page the server owed and cannot send is worth saying so; log was: " + logged);
    }

    @Test
    void filtersMappedToTheErrorDispatchRunAndRequestOnlyFiltersDoNot() throws Exception {
        pageIs("/error");
        registerFilter("error-filter", "/*", EnumSet.of(DispatcherType.ERROR));
        registerFilter("request-filter", "/*", EnumSet.of(DispatcherType.REQUEST));
        var response = new NettyHttpServletResponse();
        var request = requestFor("/boom", response);
        response.sendError(HttpResponseStatus.NOT_FOUND.code());

        errorPages.report(request, response, null);

        assertEquals(List.of("error-filter"), trace);
    }
}
