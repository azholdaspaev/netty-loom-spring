package io.github.azholdaspaev.nettyloomspring.mvc.handler;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.DefaultNettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyRequestMetadataResolver;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.servlet.DispatcherServlet;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyForwardingDispatcherTest {

    private static final HttpConnectionMetadata CONNECTION =
        new HttpConnectionMetadata("10.0.0.2", 1234, "10.0.0.1", 8080, false, "channel");

    @ParameterizedTest
    @CsvSource({
        "login, https://app.example/dir/login",
        "/login, https://app.example/login",
        "../login, https://app.example/login",
        "//other.example/login, https://other.example/login",
        "https://other.example/login, https://other.example/login",
        "?new=2, https://app.example/dir/start?new=2",
        "'', https://app.example/dir/start?old=1",
        "#fragment, https://app.example/dir/start?old=1#fragment",
        "/a%2Fb?q=%2F#part, https://app.example/a%2Fb?q=%2F#part"
    })
    void shouldResolveNativeRedirectAgainstExternalUrl(String location, String expected) throws Exception {
        var context = new DefaultNettyServletContext();
        var dispatcher = dispatcher(context, (_, response) -> response.sendRedirect(location, 307, false));
        var response = dispatch(dispatcher, "/dir/start?old=1", true);
        assertEquals(307, response.status().code());
        assertEquals(expected, response.headers().get("Location"));
        response.release();
    }

    @Test
    void shouldResolveNativeRedirectWithoutForwardedHeaders() throws Exception {
        var response = dispatch(dispatcher(new DefaultNettyServletContext(),
            (_, reply) -> reply.sendRedirect("/login")), "/dir/start", false);
        assertEquals("http://internal:8080/login", response.headers().get("Location"));
        response.release();
    }

    @Test
    void shouldUseForwardTargetForUrlAndRedirectThroughWrapper() throws Exception {
        var dispatcher = dispatcher(new DefaultNettyServletContext(), (request, response) -> {
            if (request.getRequestURI().equals("/dir/start")) {
                request.getRequestDispatcher("/target/path").forward(request, new HttpServletResponseWrapper(response));
            } else {
                assertEquals("https://app.example/target/path", request.getRequestURL().toString());
                response.sendRedirect("next");
            }
        });
        var response = dispatch(dispatcher, "/dir/start", true);
        assertEquals("https://app.example/target/next", response.headers().get("Location"));
        response.release();
    }

    @Test
    void shouldRestoreRedirectContextAfterNestedDispatchFailure() throws Exception {
        var dispatcher = dispatcher(new DefaultNettyServletContext(), (request, response) -> {
            switch (request.getRequestURI()) {
                case "/dir/start" -> {
                    assertThrows(ServletException.class,
                        () -> request.getRequestDispatcher("/middle/path").forward(request, response));
                    response.sendRedirect("after");
                }
                case "/middle/path" -> request.getRequestDispatcher("/last/path").forward(request, response);
                default -> throw new ServletException("dispatch failure");
            }
        });
        var response = dispatch(dispatcher, "/dir/start", true);
        assertEquals("https://app.example/dir/after", response.headers().get("Location"));
        response.release();
    }

    @Test
    void shouldUseErrorTargetForUrlAndRedirect() throws Exception {
        var context = new DefaultNettyServletContext();
        context.setErrorPageResolver((_, _, _) -> "/error/path");
        var dispatcher = dispatcher(context, (request, response) -> {
            if (request.getRequestURI().equals("/dir/start")) {
                response.sendError(500);
            } else {
                assertEquals("https://app.example/error/path", request.getRequestURL().toString());
                response.sendRedirect("next");
            }
        });
        var response = dispatch(dispatcher, "/dir/start", true);
        assertEquals("https://app.example/error/next", response.headers().get("Location"));
        response.release();
    }

    @Test
    void shouldRejectMalformedOriginBeforeRequestListeners() {
        var context = new DefaultNettyServletContext();
        List<String> events = new ArrayList<>();
        context.addListener(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                events.add("listener");
            }
        });
        var dispatcher = dispatcher(context, (_, _) -> events.add("servlet"));
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        request.headers().set("Forwarded", "broken");
        assertThrows(IllegalArgumentException.class,
            () -> dispatcher.handle(request, InputStream.nullInputStream(), CONNECTION, _ -> { }));
        assertTrue(events.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"OPTIONS, *, 200", "GET, /outside, 404"})
    void shouldPreserveEarlyRepliesWithMalformedForwarding(String method, String uri, int status) throws Exception {
        var context = new DefaultNettyServletContext();
        context.setContextPath("/app");
        var dispatcher = dispatcher(context, (_, _) -> { throw new AssertionError("must not reach servlet"); });
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri);
        request.headers().set("Forwarded", "broken");
        List<HttpObject> written = new ArrayList<>();
        dispatcher.handle(request, InputStream.nullInputStream(), CONNECTION, written::add);
        var response = (FullHttpResponse) written.getFirst();
        assertEquals(status, response.status().code());
        response.release();
    }

    @Test
    void shouldLeaveResponseMutableAfterInvalidRedirect() throws Exception {
        var dispatcher = dispatcher(new DefaultNettyServletContext(), (_, response) -> {
            response.getOutputStream().write("retained".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> response.sendRedirect("bad location"));
            assertFalse(response.isCommitted());
        });
        var response = dispatch(dispatcher, "/path", true);
        assertEquals("retained", response.content().toString(java.nio.charset.StandardCharsets.UTF_8));
        response.release();
    }

    private static SpringHttpRequestDispatcher dispatcher(DefaultNettyServletContext context, Action action) {
        return new SpringHttpRequestDispatcher(new DispatcherServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
                action.run(request, response);
            }
        }, context, new NettyRequestMetadataResolver(List.of("10.0.0.0/8")));
    }

    private static FullHttpResponse dispatch(SpringHttpRequestDispatcher dispatcher, String uri, boolean forwarding)
        throws Exception {
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        request.headers().set("Host", "internal:8080");
        if (forwarding) {
            request.headers().set("Forwarded", "for=203.0.113.7;proto=https;host=app.example");
        }
        List<HttpObject> written = new ArrayList<>();
        dispatcher.handle(request, InputStream.nullInputStream(), CONNECTION, written::add);
        assertEquals(1, written.size());
        return (FullHttpResponse) written.getFirst();
    }

    @FunctionalInterface
    private interface Action {
        void run(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException;
    }
}
