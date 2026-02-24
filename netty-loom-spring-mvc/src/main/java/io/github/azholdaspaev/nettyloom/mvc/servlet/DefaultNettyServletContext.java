package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.github.azholdaspaev.nettyloom.mvc.exception.NotImplementedException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultNettyServletContext implements NettyServletContext {

    private static final Logger logger = LoggerFactory.getLogger(DefaultNettyServletContext.class);

    private final Map<String, Object> attributes;
    private final Map<String, String> initParameters;

    public DefaultNettyServletContext() {
        this.attributes = new ConcurrentHashMap<>();
        this.initParameters = new ConcurrentHashMap<>();
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        return null;
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
