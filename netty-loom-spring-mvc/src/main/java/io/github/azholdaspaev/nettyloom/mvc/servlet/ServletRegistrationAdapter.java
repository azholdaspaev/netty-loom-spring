package io.github.azholdaspaev.nettyloom.mvc.servlet;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Adapter implementing ServletRegistration.Dynamic for registering servlets
 * in the Netty-based servlet context.
 */
public class ServletRegistrationAdapter implements ServletRegistration.Dynamic {

    private final String name;
    private final String className;
    private final Servlet servlet;
    private final Set<String> mappings = new HashSet<>();
    private final Map<String, String> initParameters = new HashMap<>();
    private int loadOnStartup = -1;
    private boolean asyncSupported = false;
    private String runAsRole;

    /**
     * Creates a registration for a servlet instance.
     *
     * @param name the servlet name
     * @param servlet the servlet instance
     */
    public ServletRegistrationAdapter(String name, Servlet servlet) {
        this.name = name;
        this.servlet = servlet;
        this.className = servlet.getClass().getName();
    }

    /**
     * Creates a registration for a servlet class name.
     *
     * @param name the servlet name
     * @param className the servlet class name
     */
    public ServletRegistrationAdapter(String name, String className) {
        this.name = name;
        this.className = className;
        this.servlet = null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getClassName() {
        return className;
    }

    @Override
    public Set<String> addMapping(String... urlPatterns) {
        Set<String> conflicts = new HashSet<>();
        for (String pattern : urlPatterns) {
            if (!mappings.add(pattern)) {
                conflicts.add(pattern);
            }
        }
        return conflicts;
    }

    @Override
    public Collection<String> getMappings() {
        return Collections.unmodifiableSet(mappings);
    }

    @Override
    public String getRunAsRole() {
        return runAsRole;
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        if (initParameters.containsKey(name)) {
            return false;
        }
        initParameters.put(name, value);
        return true;
    }

    @Override
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Set<String> setInitParameters(Map<String, String> initParameters) {
        Set<String> conflicts = new HashSet<>();
        for (Map.Entry<String, String> entry : initParameters.entrySet()) {
            if (this.initParameters.containsKey(entry.getKey())) {
                conflicts.add(entry.getKey());
            } else {
                this.initParameters.put(entry.getKey(), entry.getValue());
            }
        }
        return conflicts;
    }

    @Override
    public Map<String, String> getInitParameters() {
        return Collections.unmodifiableMap(initParameters);
    }

    @Override
    public void setLoadOnStartup(int loadOnStartup) {
        this.loadOnStartup = loadOnStartup;
    }

    @Override
    public Set<String> setServletSecurity(ServletSecurityElement constraint) {
        // Security constraints not supported
        return Collections.emptySet();
    }

    @Override
    public void setMultipartConfig(MultipartConfigElement multipartConfig) {
        // Multipart configuration not supported
    }

    @Override
    public void setRunAsRole(String roleName) {
        this.runAsRole = roleName;
    }

    @Override
    public void setAsyncSupported(boolean isAsyncSupported) {
        this.asyncSupported = isAsyncSupported;
    }

    /**
     * Returns the servlet instance if available.
     *
     * @return the servlet instance, or null if registered by class name
     */
    public Servlet getServlet() {
        return servlet;
    }

    /**
     * Returns the load-on-startup priority.
     *
     * @return the load-on-startup value
     */
    public int getLoadOnStartup() {
        return loadOnStartup;
    }

    /**
     * Returns whether async is supported.
     *
     * @return true if async is supported
     */
    public boolean isAsyncSupported() {
        return asyncSupported;
    }
}
