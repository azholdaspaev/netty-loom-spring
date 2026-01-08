package io.github.azholdaspaev.nettyloom.mvc.filter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter implementing FilterRegistration.Dynamic for registering filters
 * in the Netty-based servlet context.
 */
public class FilterRegistrationAdapter implements FilterRegistration.Dynamic {

    private final String name;
    private final String className;
    private final Filter filter;
    private final List<String> urlPatternMappings = new ArrayList<>();
    private final List<String> servletNameMappings = new ArrayList<>();
    private final Map<String, String> initParameters = new HashMap<>();
    private boolean asyncSupported = false;
    private EnumSet<DispatcherType> dispatcherTypes = EnumSet.of(DispatcherType.REQUEST);

    /**
     * Creates a registration for a filter instance.
     *
     * @param name the filter name
     * @param filter the filter instance
     */
    public FilterRegistrationAdapter(String name, Filter filter) {
        this.name = name;
        this.filter = filter;
        this.className = filter.getClass().getName();
    }

    /**
     * Creates a registration for a filter class name.
     *
     * @param name the filter name
     * @param className the filter class name
     */
    public FilterRegistrationAdapter(String name, String className) {
        this.name = name;
        this.className = className;
        this.filter = null;
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
    public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes,
                                          boolean isMatchAfter,
                                          String... servletNames) {
        if (dispatcherTypes != null && !dispatcherTypes.isEmpty()) {
            this.dispatcherTypes = dispatcherTypes;
        }
        if (isMatchAfter) {
            Collections.addAll(servletNameMappings, servletNames);
        } else {
            List<String> newMappings = new ArrayList<>();
            Collections.addAll(newMappings, servletNames);
            newMappings.addAll(servletNameMappings);
            servletNameMappings.clear();
            servletNameMappings.addAll(newMappings);
        }
    }

    @Override
    public Collection<String> getServletNameMappings() {
        return Collections.unmodifiableList(servletNameMappings);
    }

    @Override
    public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes,
                                         boolean isMatchAfter,
                                         String... urlPatterns) {
        if (dispatcherTypes != null && !dispatcherTypes.isEmpty()) {
            this.dispatcherTypes = dispatcherTypes;
        }
        if (isMatchAfter) {
            Collections.addAll(urlPatternMappings, urlPatterns);
        } else {
            List<String> newMappings = new ArrayList<>();
            Collections.addAll(newMappings, urlPatterns);
            newMappings.addAll(urlPatternMappings);
            urlPatternMappings.clear();
            urlPatternMappings.addAll(newMappings);
        }
    }

    @Override
    public Collection<String> getUrlPatternMappings() {
        return Collections.unmodifiableList(urlPatternMappings);
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
    public void setAsyncSupported(boolean isAsyncSupported) {
        this.asyncSupported = isAsyncSupported;
    }

    /**
     * Returns the filter instance if available.
     *
     * @return the filter instance, or null if registered by class name
     */
    public Filter getFilter() {
        return filter;
    }

    /**
     * Returns whether async is supported.
     *
     * @return true if async is supported
     */
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    /**
     * Returns the dispatcher types this filter handles.
     *
     * @return the dispatcher types
     */
    public EnumSet<DispatcherType> getDispatcherTypes() {
        return dispatcherTypes;
    }

    /**
     * Checks if this filter matches the given URL path.
     *
     * @param path the request path
     * @return true if the filter should be applied
     */
    public boolean matchesUrl(String path) {
        if (urlPatternMappings.isEmpty()) {
            return false;
        }
        for (String pattern : urlPatternMappings) {
            if (matchesPattern(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if this filter matches the given servlet name.
     *
     * @param servletName the servlet name
     * @return true if the filter should be applied
     */
    public boolean matchesServletName(String servletName) {
        return servletNameMappings.contains(servletName);
    }

    private boolean matchesPattern(String pattern, String path) {
        if (pattern.equals("/*") || pattern.equals("/")) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix);
        }
        if (pattern.startsWith("*.")) {
            String extension = pattern.substring(1);
            return path.endsWith(extension);
        }
        return pattern.equals(path);
    }
}
