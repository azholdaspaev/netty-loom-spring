package io.github.azholdaspaev.nettyloomspring.mvc.handler;

import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Every Micrometer Tracing reference lives here, loaded only once {@link SpringHttpRequestDispatcher}
 * has seen the library on the classpath; it is optional for applications.
 */
final class TracingDelegate {

    private TracingDelegate() {
    }

    /**
     * The filter's scope, and MDC with it, has closed by the time the chain returns; the context it left
     * as a request attribute still holds the span.
     */
    static String findTraceId(HttpServletRequest request) {
        return ServerHttpObservationFilter.findObservationContext(request)
            .map(context -> context.<TracingContext>get(TracingContext.class))
            .map(TracingContext::getSpan)
            .map(span -> span.context().traceId())
            .filter(traceId -> !traceId.isBlank())
            .orElse(null);
    }
}
