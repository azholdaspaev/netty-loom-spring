package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.github.azholdaspaev.nettyloom.mvc.filter.FilterRegistrationAdapter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServletContext implementation for the Netty-based servlet container.
 * Provides servlet and filter registration support required by Spring Boot.
 */
public class NettyServletContext implements ServletContext {

    private static final String SERVER_INFO = "Netty-Loom/1.0";
    private static final int MAJOR_VERSION = 6;
    private static final int MINOR_VERSION = 0;
    private static final int EFFECTIVE_MAJOR_VERSION = 6;
    private static final int EFFECTIVE_MINOR_VERSION = 0;

    private final String contextPath;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final Map<String, String> initParameters = new ConcurrentHashMap<>();
    private final Map<String, ServletRegistrationAdapter> servletRegistrations = new LinkedHashMap<>();
    private final Map<String, FilterRegistrationAdapter> filterRegistrations = new LinkedHashMap<>();
    private final Map<String, Servlet> servlets = new ConcurrentHashMap<>();
    private final Map<String, Filter> filters = new ConcurrentHashMap<>();

    /**
     * Creates a new servlet context with the specified context path.
     *
     * @param contextPath the context path (e.g., "/api" or "")
     */
    public NettyServletContext(String contextPath) {
        this.contextPath = contextPath != null ? contextPath : "";
    }

    /**
     * Creates a new servlet context with empty context path.
     */
    public NettyServletContext() {
        this("");
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public ServletContext getContext(String uripath) {
        // Only support the current context
        return this;
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return EFFECTIVE_MAJOR_VERSION;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return EFFECTIVE_MINOR_VERSION;
    }

    @Override
    public String getMimeType(String file) {
        if (file == null) {
            return null;
        }
        if (file.endsWith(".html") || file.endsWith(".htm")) {
            return "text/html";
        }
        if (file.endsWith(".css")) {
            return "text/css";
        }
        if (file.endsWith(".js")) {
            return "application/javascript";
        }
        if (file.endsWith(".json")) {
            return "application/json";
        }
        if (file.endsWith(".xml")) {
            return "application/xml";
        }
        if (file.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        return Collections.emptySet();
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        return getClass().getResource(path);
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        return getClass().getResourceAsStream(path);
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        // Request dispatching not fully supported
        return null;
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
        // Named dispatching not supported
        return null;
    }

    @Override
    public void log(String msg) {
        System.out.println("[NettyServletContext] " + msg);
    }

    @Override
    public void log(String message, Throwable throwable) {
        System.out.println("[NettyServletContext] " + message);
        throwable.printStackTrace(System.out);
    }

    @Override
    public String getRealPath(String path) {
        // Real path resolution not supported in embedded container
        return null;
    }

    @Override
    public String getServerInfo() {
        return SERVER_INFO;
    }

    @Override
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
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
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object object) {
        if (object == null) {
            removeAttribute(name);
        } else {
            attributes.put(name, object);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public String getServletContextName() {
        return "Netty-Loom Servlet Context";
    }

    // ===== Servlet Registration =====

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        throw new UnsupportedOperationException("Adding servlet by class name not supported");
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        ServletRegistrationAdapter registration = new ServletRegistrationAdapter(servletName, servlet);
        servletRegistrations.put(servletName, registration);
        servlets.put(servletName, servlet);
        return registration;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        try {
            Servlet servlet = servletClass.getDeclaredConstructor().newInstance();
            return addServlet(servletName, servlet);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate servlet: " + servletClass.getName(), e);
        }
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        throw new UnsupportedOperationException("JSP not supported");
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException("Failed to create servlet", e);
        }
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        return servletRegistrations.get(servletName);
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return Collections.unmodifiableMap(servletRegistrations);
    }

    // ===== Filter Registration =====

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        throw new UnsupportedOperationException("Adding filter by class name not supported");
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        FilterRegistrationAdapter registration = new FilterRegistrationAdapter(filterName, filter);
        filterRegistrations.put(filterName, registration);
        filters.put(filterName, filter);
        return registration;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        try {
            Filter filter = filterClass.getDeclaredConstructor().newInstance();
            return addFilter(filterName, filter);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate filter: " + filterClass.getName(), e);
        }
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException("Failed to create filter", e);
        }
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return filterRegistrations.get(filterName);
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return Collections.unmodifiableMap(filterRegistrations);
    }

    // ===== Session Configuration (stub implementation for Spring Boot compatibility) =====

    private final SessionCookieConfig sessionCookieConfig = new NoOpSessionCookieConfig();

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        return sessionCookieConfig;
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        // Session tracking not fully supported, but accept the call for Spring Boot compatibility
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return Collections.emptySet();
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return Collections.emptySet();
    }

    // ===== Listener Registration (not supported) =====

    @Override
    public void addListener(String className) {
        throw new UnsupportedOperationException("Listeners not supported");
    }

    @Override
    public <T extends EventListener> void addListener(T t) {
        // Silently ignore - Spring may try to add listeners
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        throw new UnsupportedOperationException("Listeners not supported");
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException("Listeners not supported");
    }

    // ===== JSP Configuration (not supported) =====

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return null;
    }

    // ===== Class Loading =====

    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public void declareRoles(String... roleNames) {
        // Security roles not supported
    }

    @Override
    public String getVirtualServerName() {
        return "netty-loom";
    }

    @Override
    public int getSessionTimeout() {
        return 0;
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        // Session not supported
    }

    @Override
    public String getRequestCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public void setRequestCharacterEncoding(String encoding) {
        // Character encoding configuration not supported
    }

    @Override
    public String getResponseCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public void setResponseCharacterEncoding(String encoding) {
        // Character encoding configuration not supported
    }

    // ===== Helper Methods =====

    /**
     * Returns the servlet instance with the given name.
     *
     * @param name the servlet name
     * @return the servlet instance, or null if not found
     */
    public Servlet getServlet(String name) {
        return servlets.get(name);
    }

    /**
     * Returns the filter instance with the given name.
     *
     * @param name the filter name
     * @return the filter instance, or null if not found
     */
    public Filter getFilter(String name) {
        return filters.get(name);
    }

    /**
     * Returns all registered filters in registration order.
     *
     * @return collection of filter registration adapters
     */
    public Collection<FilterRegistrationAdapter> getFilterRegistrationAdapters() {
        return Collections.unmodifiableCollection(filterRegistrations.values());
    }

    /**
     * Returns all registered filters that match the given request path.
     *
     * <p>Filters are returned in registration order. A filter matches if:
     * <ul>
     *   <li>It has no URL pattern mappings (matches all paths), or</li>
     *   <li>Its URL patterns match the given path</li>
     * </ul>
     *
     * @param requestPath the request path to match
     * @return collection of matching filters in registration order
     */
    public Collection<Filter> getMatchingFilters(String requestPath) {
        return filterRegistrations.values().stream()
                .filter(reg -> reg.matches(requestPath))
                .map(FilterRegistrationAdapter::getFilter)
                .filter(f -> f != null)
                .toList();
    }

    /**
     * No-op SessionCookieConfig for Spring Boot compatibility.
     * Session management is not fully supported.
     */
    private static class NoOpSessionCookieConfig implements SessionCookieConfig {
        private String name = "JSESSIONID";
        private String domain;
        private String path;
        private String comment;
        private boolean httpOnly = true;
        private boolean secure = false;
        private int maxAge = -1;
        private Map<String, String> attributes = new HashMap<>();

        @Override
        public void setName(String name) { this.name = name; }

        @Override
        public String getName() { return name; }

        @Override
        public void setDomain(String domain) { this.domain = domain; }

        @Override
        public String getDomain() { return domain; }

        @Override
        public void setPath(String path) { this.path = path; }

        @Override
        public String getPath() { return path; }

        @Override
        public void setComment(String comment) { this.comment = comment; }

        @Override
        public String getComment() { return comment; }

        @Override
        public void setHttpOnly(boolean httpOnly) { this.httpOnly = httpOnly; }

        @Override
        public boolean isHttpOnly() { return httpOnly; }

        @Override
        public void setSecure(boolean secure) { this.secure = secure; }

        @Override
        public boolean isSecure() { return secure; }

        @Override
        public void setMaxAge(int maxAge) { this.maxAge = maxAge; }

        @Override
        public int getMaxAge() { return maxAge; }

        @Override
        public void setAttribute(String name, String value) { attributes.put(name, value); }

        @Override
        public String getAttribute(String name) { return attributes.get(name); }

        @Override
        public Map<String, String> getAttributes() { return Collections.unmodifiableMap(attributes); }
    }
}
