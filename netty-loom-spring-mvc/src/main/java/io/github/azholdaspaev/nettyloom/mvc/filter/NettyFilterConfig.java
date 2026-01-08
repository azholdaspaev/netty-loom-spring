package io.github.azholdaspaev.nettyloom.mvc.filter;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

/**
 * FilterConfig implementation for filters registered in the Netty servlet context.
 */
public class NettyFilterConfig implements FilterConfig {

    private final String filterName;
    private final ServletContext servletContext;
    private final Map<String, String> initParameters;

    /**
     * Creates a new filter configuration.
     *
     * @param filterName the name of the filter
     * @param servletContext the servlet context
     * @param initParameters the filter's initialization parameters
     */
    public NettyFilterConfig(String filterName, ServletContext servletContext,
                             Map<String, String> initParameters) {
        this.filterName = filterName;
        this.servletContext = servletContext;
        this.initParameters = initParameters != null ? initParameters : Collections.emptyMap();
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
        return initParameters.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }
}
