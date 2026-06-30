package io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Filter implementations used by the filter-chain integration tests. Each models one acceptance
 * criterion. Destructive filters (403, throwing) are scoped to dedicated URL patterns by
 * {@link FilterTestConfig} so they never interfere with the all-paths fixtures.
 */
public final class FilterTestFixtures {

    private FilterTestFixtures() {
    }

    /** Sets {@code X-Filtered: yes} on every response and counts how often it is initialised. */
    public static final class HeaderFilter implements Filter {

        private final AtomicInteger initCount = new AtomicInteger();

        @Override
        public void init(FilterConfig filterConfig) {
            initCount.incrementAndGet();
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            ((HttpServletResponse) response).setHeader("X-Filtered", "yes");
            chain.doFilter(request, response);
        }

        public int initCount() {
            return initCount.get();
        }
    }

    /** Appends its token to the {@code X-Order} header, so invocation order is observable. */
    static final class OrderFilter implements Filter {

        private final String token;

        OrderFilter(String token) {
            this.token = token;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            ((HttpServletResponse) response).addHeader("X-Order", token);
            chain.doFilter(request, response);
        }
    }

    /**
     * Sets a fixed response header then proceeds. Used both to mark a scoped path
     * ({@code X-Scoped}) and to prove exact/query-stripped matching ({@code X-Exact} on
     * {@code /api/greeting}, which must still match a request to {@code /api/greeting?x=1}).
     */
    static final class SetHeaderFilter implements Filter {

        private final String headerName;

        SetHeaderFilter(String headerName) {
            this.headerName = headerName;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            ((HttpServletResponse) response).setHeader(headerName, "yes");
            chain.doFilter(request, response);
        }
    }

    /** Short-circuits with 403 and never proceeds, so the controller is not invoked. */
    static final class ForbiddenFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException {
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    /** Upstream error filter: catches anything thrown downstream and converts it to a 500. */
    static final class ErrorHandlingFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            try {
                chain.doFilter(request, response);
            } catch (Exception e) {
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                httpResponse.setHeader("X-Error-Handled", "yes");
            }
        }
    }

    /** Throws mid-chain, to be caught by an upstream {@link ErrorHandlingFilter}. */
    static final class ThrowingFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws ServletException {
            throw new ServletException("mid-chain failure");
        }
    }
}
