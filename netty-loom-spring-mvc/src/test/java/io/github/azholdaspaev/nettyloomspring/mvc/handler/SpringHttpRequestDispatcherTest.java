package io.github.azholdaspaev.nettyloomspring.mvc.handler;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.DefaultNettyServletContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request half of the servlet listener contract (issue #17): {@code ServletRequestListener} is what
 * Spring's {@code RequestContextListener} uses to bind {@code RequestContextHolder} and, on the way out,
 * to run the destruction callbacks of every {@code @RequestScope} bean the dispatch created. A missing
 * {@code requestDestroyed} therefore skips those {@code @PreDestroy} methods outright -- which the
 * request's virtual thread ending does nothing to clean up.
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

    /** A dispatcher whose terminal runs {@code onService} instead of Spring's {@code DispatcherServlet}. */
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

    private static FullHttpRequest get(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    @Test
    void aDispatchIsBracketedByTheRequestListener() throws Exception {
        recordRequests();
        var dispatcher = dispatcher((request, response) -> events.add("service"));

        dispatcher.handle(get("/api/ping"), CONNECTION);

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

        assertThrows(IllegalStateException.class, () -> dispatcher.handle(get("/api/ping"), CONNECTION));

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

        dispatcher.handle(get("/api/ping"), CONNECTION);

        assertNotNull(dispatched[0]);
        assertSame(dispatched[0], seen[0], "the listener must see the request the servlet is handed");
        assertSame(servletContext, seen[1]);
    }

    @Test
    void anOutOfContextRequestNeverEntersTheContext() throws Exception {
        // A URI outside server.servlet.context-path is rejected with a bare 404 before filters or the
        // servlet run. Nothing was dispatched into this context, so nothing may be announced as if it was.
        servletContext.setContextPath("/app");
        recordRequests();
        var dispatcher = dispatcher((request, response) -> events.add("service"));

        FullHttpResponse response = dispatcher.handle(get("/elsewhere"), CONNECTION);

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

        assertThrows(IllegalStateException.class, () -> dispatcher.handle(get("/api/ping"), CONNECTION));

        assertTrue(events.isEmpty(), "the servlet must not run once request setup has failed");
    }

    @Test
    void aListenerInitializedBeforeAFailingOneIsStillReleased() {
        // requestDestroyed is a release, not a notification: RequestContextListener.requestDestroyed runs
        // the destruction callbacks of every @RequestScope bean the dispatch created. fireRequestInitialized
        // runs before the try, so a later listener throwing would skip the finally entirely and leave the
        // earlier one's request scope bound with its @PreDestroy methods never run.
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
                throw new IllegalStateException("boom");
            }
        });
        var dispatcher = dispatcher((request, response) -> events.add("service"));

        assertThrows(IllegalStateException.class, () -> dispatcher.handle(get("/api/ping"), CONNECTION));

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

        assertThrows(IllegalStateException.class, () -> dispatcher.handle(get("/api/ping"), CONNECTION));

        assertTrue(events.isEmpty(), "nothing initialized, so nothing may be released; got " + events);
    }
}
