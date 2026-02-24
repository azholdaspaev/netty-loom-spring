package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.github.azholdaspaev.nettyloom.mvc.exception.NotImplementedException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultNettyServletContext implements NettyServletContext {

    private static final Logger logger = LoggerFactory.getLogger(DefaultNettyServletContext.class);

    private final Map<String, Object> attributes;
    private final Map<String, String> initParameters;
    private final ClassLoader classLoader;

    public DefaultNettyServletContext() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public DefaultNettyServletContext(ClassLoader classLoader) {
        this.attributes = new ConcurrentHashMap<>();
        this.initParameters = new ConcurrentHashMap<>();
        this.classLoader = classLoader;
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        if (path == null || !path.startsWith("/")) {
            throw new MalformedURLException("Path must start with '/': " + path);
        }
        String normalized = normalizePath(path);
        if (normalized == null) {
            return null;
        }
        return classLoader.getResource(normalized);
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        try {
            URL url = getResource(path);
            if (url == null) {
                return null;
            }
            return url.openStream();
        } catch (IOException e) {
            logger.debug("Failed to open resource stream for path: {}", path, e);
            return null;
        }
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
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
    public void log(String msg) {
        logger.info(msg);
    }

    @Override
    public void log(String message, Throwable throwable) {
        logger.info(message, throwable);
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
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        return new NoOpServletRegistration(servletName);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        return new NoOpFilterRegistration(filterName);
    }

    private static String normalizePath(String path) {
        String[] segments = path.split("/");
        List<String> resolved = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (resolved.isEmpty()) {
                    return null;
                }
                resolved.removeLast();
            } else {
                resolved.add(segment);
            }
        }
        return String.join("/", resolved);
    }

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
                EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... servletNames) {
            throw new NotImplementedException("Not implemented");
        }

        @Override
        public Collection<String> getServletNameMappings() {
            throw new NotImplementedException("Not implemented");
        }

        @Override
        public void addMappingForUrlPatterns(
                EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... urlPatterns) {}

        @Override
        public Collection<String> getUrlPatternMappings() {
            throw new NotImplementedException("Not implemented");
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getClassName() {
            throw new NotImplementedException("Not implemented");
        }

        @Override
        public boolean setInitParameter(String name, String value) {
            throw new NotImplementedException("Not implemented");
        }

        @Override
        public String getInitParameter(String name) {
            throw new NotImplementedException("Not implemented");
        }

        @Override
        public Set<String> setInitParameters(Map<String, String> initParameters) {
            throw new NotImplementedException("Not implemented");
        }

        @Override
        public Map<String, String> getInitParameters() {
            throw new NotImplementedException("Not implemented");
        }
    }
}
