package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultNettyServletContext implements NettyServletContext {

    private static final Logger log = LoggerFactory.getLogger(DefaultNettyServletContext.class);

    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> initParameters = new ConcurrentHashMap<>();
    private final Map<String, ServletRegistration> servletRegistrations = new LinkedHashMap<>();
    private final Map<String, FilterRegistration> filterRegistrations = new LinkedHashMap<>();

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
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        return initParameters.putIfAbsent(name, value) == null;
    }

    private ServletRegistration.Dynamic registerServlet(String servletName, String className) {
        var registration = new NettyServletRegistration(servletName, className);
        servletRegistrations.put(servletName, registration);
        return registration;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        return registerServlet(servletName, className);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        return registerServlet(servletName, servlet.getClass().getName());
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        return registerServlet(servletName, servletClass.getName());
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        return servletRegistrations.get(servletName);
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return Collections.unmodifiableMap(servletRegistrations);
    }

    private FilterRegistration.Dynamic registerFilter(String filterName, String className) {
        var registration = new NettyFilterRegistration(filterName, className);
        filterRegistrations.put(filterName, registration);
        return registration;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        return registerFilter(filterName, className);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        return registerFilter(filterName, filter.getClass().getName());
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        return registerFilter(filterName, filterClass.getName());
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return filterRegistrations.get(filterName);
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return Collections.unmodifiableMap(filterRegistrations);
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
    public Set<String> getResourcePaths(String path) {
        return null;
    }

    @Override
    public String getRealPath(String path) {
        return null;
    }

    @Override
    public String getContextPath() {
        return "";
    }

    @Override
    public String getServletContextName() {
        return "NettyServletContext";
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
    public String getServerInfo() {
        return "Netty-Loom";
    }

    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public void log(String msg) {
        log.info(msg);
    }

    @Override
    public void log(String message, Throwable throwable) {
        log.error(message, throwable);
    }

    // --- Registration implementations ---

    private abstract static class AbstractNettyRegistration {

        private final String name;
        private final String className;
        private final Map<String, String> initParameters = new LinkedHashMap<>();

        AbstractNettyRegistration(String name, String className) {
            this.name = name;
            this.className = className;
        }

        public String getName() {
            return name;
        }

        public String getClassName() {
            return className;
        }

        public boolean setInitParameter(String name, String value) {
            return initParameters.putIfAbsent(name, value) == null;
        }

        public String getInitParameter(String name) {
            return initParameters.get(name);
        }

        public Set<String> setInitParameters(Map<String, String> initParameters) {
            var conflicts = new HashSet<String>();
            for (var entry : initParameters.entrySet()) {
                if (this.initParameters.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    conflicts.add(entry.getKey());
                }
            }
            return conflicts;
        }

        public Map<String, String> getInitParameters() {
            return Collections.unmodifiableMap(initParameters);
        }

        public void setAsyncSupported(boolean isAsyncSupported) {
        }
    }

    private static class NettyServletRegistration extends AbstractNettyRegistration implements ServletRegistration.Dynamic {

        private final Set<String> mappings = new LinkedHashSet<>();

        NettyServletRegistration(String name, String className) {
            super(name, className);
        }

        @Override
        public Set<String> addMapping(String... urlPatterns) {
            Collections.addAll(mappings, urlPatterns);
            return Collections.emptySet();
        }

        @Override
        public Collection<String> getMappings() {
            return Collections.unmodifiableSet(mappings);
        }

        @Override
        public String getRunAsRole() {
            return null;
        }

        @Override
        public void setLoadOnStartup(int loadOnStartup) {
        }

        @Override
        public Set<String> setServletSecurity(jakarta.servlet.ServletSecurityElement constraint) {
            return Collections.emptySet();
        }

        @Override
        public void setMultipartConfig(jakarta.servlet.MultipartConfigElement multipartConfig) {
        }

        @Override
        public void setRunAsRole(String roleName) {
        }
    }

    private static class NettyFilterRegistration extends AbstractNettyRegistration implements FilterRegistration.Dynamic {

        NettyFilterRegistration(String name, String className) {
            super(name, className);
        }

        @Override
        public void addMappingForServletNames(java.util.EnumSet<jakarta.servlet.DispatcherType> dispatcherTypes,
                                               boolean isMatchAfter, String... servletNames) {
        }

        @Override
        public Collection<String> getServletNameMappings() {
            return Collections.emptySet();
        }

        @Override
        public void addMappingForUrlPatterns(java.util.EnumSet<jakarta.servlet.DispatcherType> dispatcherTypes,
                                              boolean isMatchAfter, String... urlPatterns) {
        }

        @Override
        public Collection<String> getUrlPatternMappings() {
            return Collections.emptySet();
        }
    }
}
