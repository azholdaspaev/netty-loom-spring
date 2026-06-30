package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;

import java.util.Collections;
import java.util.Enumeration;

/**
 * Minimal {@link FilterConfig} passed to {@code Filter#init} at startup. Mirror of
 * {@link NettyServletConfig}.
 */
public class NettyFilterConfig implements FilterConfig {

    private final String filterName;
    private final NettyServletContext servletContext;

    public NettyFilterConfig(String filterName, NettyServletContext servletContext) {
        this.filterName = filterName;
        this.servletContext = servletContext;
    }

    @Override
    public String getFilterName() {
        return filterName;
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public String getInitParameter(String name) {
        return null;
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.emptyEnumeration();
    }
}
