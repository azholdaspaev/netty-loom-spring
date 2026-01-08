package io.github.azholdaspaev.nettyloom.mvc.servlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

/**
 * ServletConfig implementation for servlets registered in the Netty servlet context.
 */
public class NettyServletConfig implements ServletConfig {

    private final String servletName;
    private final ServletContext servletContext;
    private final Map<String, String> initParameters;

    /**
     * Creates a new servlet configuration.
     *
     * @param servletName the name of the servlet
     * @param servletContext the servlet context
     * @param initParameters the servlet's initialization parameters
     */
    public NettyServletConfig(String servletName, ServletContext servletContext,
                              Map<String, String> initParameters) {
        this.servletName = servletName;
        this.servletContext = servletContext;
        this.initParameters = initParameters != null ? initParameters : Collections.emptyMap();
    }

    @Override
    public String getServletName() {
        return servletName;
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
