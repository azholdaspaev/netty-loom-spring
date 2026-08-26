package io.github.azholdaspaev.nettyloomspring.autoconfigure.forward.app;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;

public final class ForwardTestFixtures {

    public static final String TRACE = "trace";

    private ForwardTestFixtures() {
    }

    /**
     * Appends its own name to a request attribute, so one response can report which filters ran and in
     * what order across both dispatches.
     */
    public static final class TraceFilter implements Filter {

        private final String name;

        public TraceFilter(String name) {
            this.name = name;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            Object seen = request.getAttribute(TRACE);
            request.setAttribute(TRACE, seen == null ? name : seen + "," + name);
            chain.doFilter(request, response);
        }
    }
}
