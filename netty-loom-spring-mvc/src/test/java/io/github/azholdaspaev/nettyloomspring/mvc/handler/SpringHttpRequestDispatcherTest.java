package io.github.azholdaspaev.nettyloomspring.mvc.handler;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.DefaultNettyServletContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.servlet.DispatcherServlet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request half of the servlet listener contract (issue #17): {@code ServletRequestListener} is what
 * Spring's {@code RequestContextListener} uses to bind {@code RequestContextHolder} and, on the way out,
 * to run the destruction callbacks of every {@code @RequestScope} bean the dispatch created.
 */
class SpringHttpRequestDispatcherTest {

    private static final HttpConnectionMetadata CONNECTION =
        new HttpConnectionMetadata("198.51.100.2", 1234, "198.51.100.9", 8080, false);

    private DefaultNettyServletContext servletContext;
    private List<String> events;

    @BeforeEach
    void setUp() {
        servletContext = new DefaultNettyServletContext();
        events = new ArrayList<>();
    }

    /**
     * A dispatcher whose terminal runs {@code onService} instead of Spring's {@code DispatcherServlet}.
     */
    private SpringHttpRequestDispatcher dispatcher(BiConsumer<HttpServletRequest, HttpServletResponse> onService) {
        return new SpringHttpRequestDispatcher(new DispatcherServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) {
                onService.accept(request, response);
            }
        }, servletContext);
    }

    private void recordRequests() {
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                events.add("initialized");
            }

            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                events.add("destroyed");
            }
        });
    }

    /**
     * Runs a dispatch against a connection that records what it is given, and returns the one buffered
     * response a completed dispatch writes.
     */
    private static FullHttpResponse dispatch(SpringHttpRequestDispatcher dispatcher, FullHttpRequest request)
        throws Exception {
        List<HttpObject> written = new ArrayList<>();
        dispatcher.handle(request, CONNECTION, written::add);
        assertEquals(1, written.size(), "a response that fits the buffer is one part; got " + written);
        return (FullHttpResponse) written.get(0);
    }

    private static FullHttpRequest get(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    @Test
    void aDispatchIsBracketedByTheRequestListener() throws Exception {
        recordRequests();
        var dispatcher = dispatcher((request, response) -> events.add("service"));

        dispatch(dispatcher, get("/api/ping"));

        assertEquals(List.of("initialized", "service", "destroyed"), events);
    }

    @Test
    void requestDestroyedStillFiresWhenTheDispatchThrows() {
        // A handler that blows up is exactly when request scope most needs unbinding: without the
        // finally, RequestContextHolder would stay bound to a request that is already gone.
        recordRequests();
        var dispatcher = dispatcher((request, response) -> {
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, () -> dispatch(dispatcher, get("/api/ping")));

        assertEquals(List.of("initialized", "destroyed"), events);
    }

    @Test
    void theEventNamesTheRequestBeingDispatched() throws Exception {
        var seen = new Object[2];
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                seen[0] = event.getServletRequest();
                seen[1] = event.getServletContext();
            }
        });
        var dispatched = new ServletRequest[1];
        var dispatcher = dispatcher((request, response) -> dispatched[0] = request);

        dispatch(dispatcher, get("/api/ping"));

        assertNotNull(dispatched[0]);
        assertSame(dispatched[0], seen[0], "the listener must see the request the servlet is handed");
        assertSame(servletContext, seen[1]);
    }

    @Test
    void theContextsCookieSameSiteResolverReachesTheResponse() throws Exception {
        var dispatcher = dispatcher((request, response) -> response.addCookie(new Cookie("tracker", "t")));
        // Set after the dispatcher exists: the factory installs the resolver during getWebServer(), long
        // after this bean is constructed, so a resolver captured at construction would always be the
        // default. Reading it per request is what makes the bean order irrelevant.
        servletContext.setCookieSameSiteResolver(cookie -> "Strict");

        FullHttpResponse response = dispatch(dispatcher, get("/api/ping"));

        String setCookie = response.headers().get(HttpHeaderNames.SET_COOKIE);
        assertTrue(setCookie.contains("SameSite=Strict"), "Actual: " + setCookie);
    }

    // --- Error pages (issue #38) ---

    private void errorPageIs(String path) {
        servletContext.setErrorPageResolver((status, failure, rootCause) -> path);
    }

    private static void write(HttpServletResponse response, String body) {
        try {
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void sendErrorIsAnsweredByTheErrorPage() throws Exception {
        errorPageIs("/error");
        var dispatcher = dispatcher((request, response) -> {
            if (request.getServletPath().equals("/error")) {
                write(response, "the error page");
                return;
            }
            assertDoesNotThrow(() -> response.sendError(HttpServletResponse.SC_NOT_FOUND));
        });

        FullHttpResponse response = dispatch(dispatcher, get("/api/missing"));

        assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status().code());
        assertEquals("the error page", response.content().toString(StandardCharsets.UTF_8));
    }

    @Test
    void aHandlerExceptionWithAnErrorPageIsAnsweredInsteadOfPropagating() throws Exception {
        errorPageIs("/error");
        var dispatcher = dispatcher((request, response) -> {
            if (request.getServletPath().equals("/error")) {
                write(response, "the error page");
                return;
            }
            throw new IllegalStateException("boom");
        });

        FullHttpResponse response = dispatch(dispatcher, get("/api/ping"));

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.status().code());
        assertEquals("the error page", response.content().toString(StandardCharsets.UTF_8));
    }

    @Test
    void aHandlerExceptionWithNoErrorPageStillPropagates() {
        var dispatcher = dispatcher((request, response) -> {
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, () -> dispatch(dispatcher, get("/api/ping")),
            "with no page registered the failure still has to reach the connection");
    }

    @Test
    void theErrorPageRunsBeforeTheRequestListenerIsToldTheRequestEnded() throws Exception {
        errorPageIs("/error");
        recordRequests();
        var dispatcher = dispatcher((request, response) -> {
            if (request.getServletPath().equals("/error")) {
                events.add("error-page");
                return;
            }
            events.add("service");
            assertDoesNotThrow(() -> response.sendError(HttpServletResponse.SC_NOT_FOUND));
        });

        dispatch(dispatcher, get("/api/missing"));

        assertEquals(List.of("initialized", "service", "error-page", "destroyed"), events,
            "requestDestroyed runs every @RequestScope destruction callback, so the error page cannot "
                + "run after it");
    }

    @Test
    void theErrorPageIsDispatchedOnlyOnceWhenItAlsoCallsSendError() throws Exception {
        errorPageIs("/error");
        var dispatched = new int[1];
        var dispatcher = dispatcher((request, response) -> {
            if (request.getServletPath().equals("/error")) {
                dispatched[0]++;
                assertDoesNotThrow(() -> response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE));
                return;
            }
            assertDoesNotThrow(() -> response.sendError(HttpServletResponse.SC_NOT_FOUND));
        });

        FullHttpResponse response = dispatch(dispatcher, get("/api/missing"));

        assertEquals(1, dispatched[0], "an error page that errors again must not loop");
        assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, response.status().code());
    }

    @Test
    void anErrorPageThatThrowsPropagatesRatherThanAnsweringTwice() {
        errorPageIs("/error");
        var dispatcher = dispatcher((request, response) -> {
            if (request.getServletPath().equals("/error")) {
                throw new IllegalArgumentException("the error page itself is broken");
            }
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalArgumentException.class, () -> dispatch(dispatcher, get("/api/ping")));
    }

    @Test
    void theOutOfContextFourOhFourNeverReachesAnErrorPage() throws Exception {
        servletContext.setContextPath("/app");
        errorPageIs("/error");
        var dispatcher = dispatcher((request, response) -> write(response, "the error page"));

        FullHttpResponse response = dispatch(dispatcher, get("/elsewhere"));

        assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status().code());
        assertEquals("", response.content().toString(StandardCharsets.UTF_8),
            "the request never entered the context, so it is owed no page from it");
    }

    @Test
    void anOutOfContextRequestNeverEntersTheContext() throws Exception {
        // A URI outside server.servlet.context-path is rejected with a bare 404 before filters or the
        // servlet run. Nothing was dispatched into this context, so nothing may be announced as if it was.
        servletContext.setContextPath("/app");
        recordRequests();
        var dispatcher = dispatcher((request, response) -> events.add("service"));

        FullHttpResponse response = dispatch(dispatcher, get("/elsewhere"));

        assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status().code());
        assertTrue(events.isEmpty(), "an out-of-context request fires no request listener; got " + events);
    }

    @Test
    void aFailingRequestListenerAbortsTheDispatch() {
        // requestInitialized propagates: a listener that could not set up request scope has left the
        // servlet unable to run correctly, so the exception handler turns it into a status code rather
        // than the application silently serving a request with no scope bound.
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                throw new IllegalStateException("boom");
            }
        });
        var dispatcher = dispatcher((request, response) -> events.add("service"));

        assertThrows(IllegalStateException.class, () -> dispatch(dispatcher, get("/api/ping")));

        assertTrue(events.isEmpty(), "the servlet must not run once request setup has failed");
    }

    /**
     * Delivers a checked or unchecked failure where the compiler expects none.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * The ordinary failure, and the linkage error a listener touching a missing class actually raises.
     */
    static Stream<Throwable> theTwoFailureShapes() {
        return Stream.of(new IllegalStateException("boom"), new NoClassDefFoundError("com/example/Missing"));
    }

    @ParameterizedTest
    @MethodSource("theTwoFailureShapes")
    void aListenerInitializedBeforeAFailingOneIsStillReleased(Throwable failure) {
        // requestDestroyed is a release, not a notification: RequestContextListener.requestDestroyed runs
        // the destruction callbacks of every @RequestScope bean the dispatch created. fireRequestInitialized
        // runs before the try, so a later listener throwing would skip the finally entirely and leave the
        // earlier one's request scope bound with its @PreDestroy methods never run. An Error has to reach
        // the unwind for the same reason -- nothing else would release it.
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                events.add("first:initialized");
            }

            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                events.add("first:destroyed");
            }
        });
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                sneakyThrow(failure);
            }
        });
        var dispatcher = dispatcher((request, response) -> events.add("service"));

        assertThrows(failure.getClass(), () -> dispatch(dispatcher, get("/api/ping")));

        assertEquals(List.of("first:initialized", "first:destroyed"), events,
            "the prefix that did initialize must be released before the failure leaves");
    }

    @Test
    void aListenerThatNeverInitializedIsNotDestroyed() {
        // The other half of the same rule: releasing a listener that was never set up is what the
        // symmetric contextInitialized fix exists to prevent, and it applies per request too.
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                events.add("failing:destroyed");
            }
        });
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                events.add("later:destroyed");
            }
        });
        var dispatcher = dispatcher((request, response) -> events.add("service"));

        assertThrows(IllegalStateException.class, () -> dispatch(dispatcher, get("/api/ping")));

        assertTrue(events.isEmpty(), "nothing initialized, so nothing may be released; got " + events);
    }

    @Test
    void aThrowingRequestDestroyedListenerDoesNotReplaceTheHandlersFailure() {
        // fireRequestDestroyed runs in the dispatcher's finally, so propagating there would replace any
        // in-flight exception -- losing the handler's failure on every request while the listener stayed
        // broken -- and would skip the listeners below it.
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                events.add("outer:destroyed");
            }
        });
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                throw new IllegalStateException("audit sink is down");
            }
        });
        var dispatcher = dispatcher((request, response) -> {
            throw new UnsupportedOperationException("the handler's own failure");
        });

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
            () -> dispatch(dispatcher, get("/api/ping")));

        assertEquals("the handler's own failure", thrown.getMessage());
        assertTrue(events.contains("outer:destroyed"),
            "a listener below the failing one must still be released; got " + events);
    }

    @Test
    void aThrowingRequestDestroyedListenerDoesNotBreakASuccessfulResponse() {
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                throw new IllegalStateException("audit sink is down");
            }
        });
        var dispatcher = dispatcher((request, response) -> response.setStatus(HttpServletResponse.SC_OK));

        FullHttpResponse response =
            assertDoesNotThrow(() -> dispatch(dispatcher, get("/api/ping")));

        assertEquals(HttpServletResponse.SC_OK, response.status().code());
    }

    @Test
    void anErrorFromAReleasedListenerDoesNotReplaceTheOriginalFailure() {
        // An Error escaping the release loop would replace the original failure -- the one the caller
        // needs to see -- and skip the listeners below it.
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                events.add("outer:destroyed");
            }
        });
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                events.add("middle:initialized");
            }

            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                throw new NoClassDefFoundError("com/example/Missing");
            }
        });
        servletContext.addListener(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                throw new IllegalStateException("the original failure");
            }
        });
        var dispatcher = dispatcher((request, response) -> events.add("service"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> dispatch(dispatcher, get("/api/ping")));

        assertEquals("the original failure", thrown.getMessage());
        assertTrue(events.contains("outer:destroyed"),
            "a listener below the one that failed to release must still be released; got " + events);
    }

    @Test
    void aHandlerThatFlushesMidDispatchStreamsThroughTheConnection() throws Exception {
        var dispatcher = dispatcher((request, response) -> {
            // The stubbed servlet cannot throw, so the failure is surfaced unchecked.
            try {
                response.getOutputStream().write("event one".getBytes(StandardCharsets.UTF_8));
                response.flushBuffer();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        List<HttpObject> written = new ArrayList<>();

        dispatcher.handle(get("/api/stream"), CONNECTION, written::add);

        assertEquals(3, written.size(), "head, body and terminator each reach the connection; got " + written);
        assertInstanceOf(LastHttpContent.class, written.get(2));
    }

    // --- Forward dispatch (issue #182) ---

    private SpringHttpRequestDispatcher forwardingDispatcher() {
        return dispatcher((request, response) -> {
            try {
                // The servlet is re-entered by its own forward; branching on the URI is what stops
                // the second pass forwarding again.
                if ("/target".equals(request.getRequestURI())) {
                    events.add("target");
                    response.getOutputStream().write("target".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                events.add("source");
                response.getOutputStream().write("source".getBytes(StandardCharsets.UTF_8));
                request.getRequestDispatcher("/target").forward(request, response);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (ServletException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void aForwardedDispatchWritesExactlyOneResponse() throws Exception {
        FullHttpResponse response = dispatch(forwardingDispatcher(), get("/source"));

        assertEquals("target", response.content().toString(StandardCharsets.UTF_8),
            "only the terminal dispatch completes the response, and the source's output is reset away");
    }

    @Test
    void aForwardDoesNotRefireTheRequestListener() throws Exception {
        recordRequests();

        dispatch(forwardingDispatcher(), get("/source"));

        assertEquals(List.of("initialized", "source", "target", "destroyed"), events,
            "the listener pair brackets the request, not each dispatch");
    }
}
