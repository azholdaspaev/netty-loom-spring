package io.github.azholdaspaev.nettyloom.mvc.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * FilterChain implementation that executes registered filters in sequence
 * and terminates by invoking the target servlet (typically DispatcherServlet).
 *
 * <p>The chain execution follows the standard servlet filter pattern:
 * <pre>
 * FilterChainAdapter.doFilter() [index=0]
 * ├─ Filter 1.doFilter(req, resp, this)
 * │  └─ chain.doFilter() [index=1]
 * │     ├─ Filter 2.doFilter(req, resp, this)
 * │     │  └─ chain.doFilter() [index=2]
 * │     │     └─ No more filters → Servlet.service()
 * │     └─ Filter 2's post-processing
 * └─ Filter 1's post-processing
 * </pre>
 */
public class FilterChainAdapter implements FilterChain {

    private final Filter[] filters;
    private final Servlet terminalServlet;
    private int currentIndex = 0;

    /**
     * Creates a filter chain with the given filters and terminal servlet.
     *
     * @param filters the filters to execute in order
     * @param terminalServlet the servlet to invoke after all filters (typically DispatcherServlet)
     */
    public FilterChainAdapter(Filter[] filters, Servlet terminalServlet) {
        this.filters = filters != null ? filters : new Filter[0];
        this.terminalServlet = terminalServlet;
    }

    /**
     * Creates a filter chain from a list of filters and terminal servlet.
     *
     * @param filters the filters to execute in order
     * @param terminalServlet the servlet to invoke after all filters
     */
    public FilterChainAdapter(List<Filter> filters, Servlet terminalServlet) {
        this(filters != null ? filters.toArray(new Filter[0]) : null, terminalServlet);
    }

    /**
     * Creates a filter chain with no filters, directly invoking the servlet.
     *
     * @param terminalServlet the servlet to invoke
     */
    public FilterChainAdapter(Servlet terminalServlet) {
        this(new Filter[0], terminalServlet);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {

        if (currentIndex < filters.length) {
            // Execute next filter in chain
            Filter filter = filters[currentIndex++];
            filter.doFilter(request, response, this);
        } else {
            // All filters executed, invoke terminal servlet
            if (terminalServlet != null) {
                terminalServlet.service(request, response);
            }
        }
    }

    /**
     * Returns the number of filters in this chain.
     *
     * @return the filter count
     */
    public int getFilterCount() {
        return filters.length;
    }

    /**
     * Returns the terminal servlet.
     *
     * @return the terminal servlet
     */
    public Servlet getTerminalServlet() {
        return terminalServlet;
    }

    /**
     * Returns the current filter index.
     * Useful for testing and debugging.
     *
     * @return the current index in the filter chain
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * Resets the chain to allow re-execution from the beginning.
     * This is typically not needed in normal request processing.
     */
    public void reset() {
        currentIndex = 0;
    }
}
