package io.github.azholdaspaev.nettyloomspring.autoconfigure.error.app;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class ErrorTestFixtures {

    private ErrorTestFixtures() {
    }

    public static class TraceFilter implements Filter {

        private final String header;

        public TraceFilter(String header) {
            this.header = header;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            ((HttpServletResponse) response).setHeader(header, "ran");
            chain.doFilter(request, response);
        }
    }
}
