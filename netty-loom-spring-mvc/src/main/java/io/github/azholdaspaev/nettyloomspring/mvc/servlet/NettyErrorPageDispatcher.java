package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpExceptionHandler;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * Answers a failed request with its registered error page, the container half of the {@code /error}
 * contract (issue #38). Modelled on Tomcat's {@code StandardHostValve}, which reports after the whole
 * chain has returned rather than from inside it.
 */
public class NettyErrorPageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NettyErrorPageDispatcher.class);

    private final NettyServletContext context;

    public NettyErrorPageDispatcher(NettyServletContext context) {
        this.context = context;
    }

    /**
     * Renders the error page for a dispatch that failed, and reports whether it did. A {@code false}
     * answer leaves the response untouched, so the caller still owes the client whatever it holds.
     */
    public boolean report(HttpServletRequest request, NettyHttpServletResponse response, Throwable failure)
        throws ServletException, IOException {
        if (failure == null && !response.isErrorSent()) {
            return false;
        }
        Throwable rootCause = rootCauseOf(failure);
        int status = statusFor(response, failure);
        String path = context.getErrorPageResolver().resolve(status, rootCause);
        if (path == null) {
            return false;
        }
        if (response.isHeadWritten()) {
            log.warn("Cannot answer {} with error page {}: the response is already on the wire",
                request.getRequestURI(), path, rootCause);
            return false;
        }
        NettyRequestDispatcher page = context.getDispatchFactory().resolve(path);
        if (page == null) {
            log.warn("Error page {} does not resolve to a path inside this context", path);
            return false;
        }
        if (rootCause != null) {
            log.error("Answering {} with error page {} after an uncaught failure",
                request.getRequestURI(), path, rootCause);
        }
        String message = rootCause != null ? rootCause.getMessage() : response.getErrorMessage();
        response.reopenForErrorPage();
        response.setStatus(status);
        setErrorAttributes(request, status, message, rootCause);
        page.dispatch(request, response, DispatcherType.ERROR);
        return true;
    }

    // DispatcherServlet wraps what a handler throws, so the wrapper is never what an application
    // registered a page for; Tomcat's StandardHostValve.throwable unwraps for the same reason.
    private static Throwable rootCauseOf(Throwable failure) {
        if (failure instanceof ServletException wrapper && wrapper.getRootCause() != null) {
            return wrapper.getRootCause();
        }
        return failure;
    }

    // The failure as thrown rather than its root cause: FrameworkServlet wraps whatever a handler
    // threw, so the wrapper is what tells a controller failure from a filter's, and only the latter
    // keeps the pipeline's mapped status. Whatever the response already held is not consulted --
    // Tomcat's StandardWrapperValve.exception likewise sets 500 over it before a page is resolved.
    private static int statusFor(NettyHttpServletResponse response, Throwable failure) {
        return failure == null ? response.getStatus() : HttpExceptionHandler.statusFor(failure).code();
    }

    private static void setErrorAttributes(HttpServletRequest request, int status, String message,
                                           Throwable rootCause) {
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        // Never null: setAttribute(name, null) removes, and an absent attribute reads as "no message
        // recorded" rather than the empty one the spec promises (Tomcat bz 69444).
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, Objects.requireNonNullElse(message, ""));
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
        request.setAttribute(RequestDispatcher.ERROR_METHOD, request.getMethod());
        request.setAttribute(RequestDispatcher.ERROR_QUERY_STRING, request.getQueryString());
        if (rootCause != null) {
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, rootCause);
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE, rootCause.getClass());
        }
    }
}
