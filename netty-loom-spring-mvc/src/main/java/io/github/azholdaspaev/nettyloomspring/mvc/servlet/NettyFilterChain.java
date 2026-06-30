package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * A {@link FilterChain} over an ordered list of applicable filters that terminates by invoking
 * a terminal {@link FilterChain} (typically {@code DispatcherServlet::service}). One instance
 * per request — the cursor is stateful, so it must not be shared across threads.
 *
 * <p>Each {@link #doFilter(ServletRequest, ServletResponse)} hands the request/response it
 * <em>receives</em> to the next filter and to the terminal, so request/response wrappers
 * installed by a filter propagate downstream. A filter that does not call {@code doFilter}
 * short-circuits the chain: the terminal never runs and the controller is never invoked.
 */
public class NettyFilterChain implements FilterChain {

    private final List<RegisteredFilter> filters;
    private final FilterChain terminal;
    private int index;

    public NettyFilterChain(List<RegisteredFilter> filters, FilterChain terminal) {
        this.filters = filters;
        this.terminal = terminal;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
        if (index < filters.size()) {
            RegisteredFilter current = filters.get(index++);
            current.filter().doFilter(request, response, this);
        } else {
            terminal.doFilter(request, response);
        }
    }
}
