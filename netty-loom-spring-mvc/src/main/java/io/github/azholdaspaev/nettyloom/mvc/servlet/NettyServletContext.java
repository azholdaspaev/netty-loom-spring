package io.github.azholdaspaev.nettyloom.mvc.servlet;

import jakarta.servlet.*;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NettyServletContext implements ServletContext {

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    @Override
    public String getContextPath() {
        return "";
    }

    @Override
    public ServletContext getContext(String uripath) {
        return null;
    }

    @Override
    public int getMajorVersion() {
        return 6;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 6;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 0;
    }

    @Override
    public String getMimeType(String file) {
        return null;
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        return null;
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        return null;
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        return null;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return null;
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
        return null;
    }

    @Override
    public void log(String msg) {}

    @Override
    public void log(String message, Throwable throwable) {}

    @Override
    public String getRealPath(String path) {
        return null;
    }

    @Override
    public String getServerInfo() {
        return "netty-loom";
    }

    @Override
    public String getInitParameter(String name) {
        return null;
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.emptyEnumeration();
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        return false;
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
        if (object != null) {
            attributes.put(name, object);
        } else {
            attributes.remove(name);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public String getServletContextName() {
        return "netty-loom";
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        return new NoOpServletRegistration(servletName);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        return new NoOpServletRegistration(servletName);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        return new NoOpServletRegistration(servletName);
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        return null;
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        return null;
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return Map.of();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        return new NoOpFilterRegistration(filterName);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        return new NoOpFilterRegistration(filterName);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        return new NoOpFilterRegistration(filterName);
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return null;
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return Map.of();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        return null;
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {}

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return Set.of();
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return Set.of();
    }

    @Override
    public void addListener(String className) {}

    @Override
    public <T extends EventListener> void addListener(T t) {}

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {}

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return null;
    }

    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public void declareRoles(String... roleNames) {}

    @Override
    public String getVirtualServerName() {
        return "netty-loom";
    }

    @Override
    public int getSessionTimeout() {
        return 0;
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {}

    @Override
    public String getRequestCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public void setRequestCharacterEncoding(String encoding) {}

    @Override
    public String getResponseCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public void setResponseCharacterEncoding(String encoding) {}

    private static class NoOpServletRegistration implements ServletRegistration.Dynamic {

        private final String name;

        NoOpServletRegistration(String name) {
            this.name = name;
        }

        @Override
        public void setLoadOnStartup(int loadOnStartup) {}

        @Override
        public Set<String> setServletSecurity(ServletSecurityElement constraint) {
            return Set.of();
        }

        @Override
        public void setMultipartConfig(MultipartConfigElement multipartConfig) {}

        @Override
        public void setRunAsRole(String roleName) {}

        @Override
        public void setAsyncSupported(boolean isAsyncSupported) {}

        @Override
        public Set<String> addMapping(String... urlPatterns) {
            return Set.of();
        }

        @Override
        public Collection<String> getMappings() {
            return Set.of();
        }

        @Override
        public String getRunAsRole() {
            return null;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getClassName() {
            return "";
        }

        @Override
        public boolean setInitParameter(String name, String value) {
            return true;
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Set<String> setInitParameters(Map<String, String> initParameters) {
            return Set.of();
        }

        @Override
        public Map<String, String> getInitParameters() {
            return Map.of();
        }
    }

    private static class NoOpFilterRegistration implements FilterRegistration.Dynamic {

        private final String name;

        NoOpFilterRegistration(String name) {
            this.name = name;
        }

        @Override
        public void setAsyncSupported(boolean isAsyncSupported) {}

        @Override
        public void addMappingForServletNames(
                EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... servletNames) {}

        @Override
        public Collection<String> getServletNameMappings() {
            return Set.of();
        }

        @Override
        public void addMappingForUrlPatterns(
                EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... urlPatterns) {}

        @Override
        public Collection<String> getUrlPatternMappings() {
            return Set.of();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getClassName() {
            return "";
        }

        @Override
        public boolean setInitParameter(String name, String value) {
            return true;
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Set<String> setInitParameters(Map<String, String> initParameters) {
            return Set.of();
        }

        @Override
        public Map<String, String> getInitParameters() {
            return Map.of();
        }
    }
}
