package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.netty.handler.codec.http.QueryStringDecoder;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class NettyForwardRequestWrapper extends HttpServletRequestWrapper {

    private final NettyDispatchFactory factory;
    private final String targetPath;
    private final String queryString;
    private final Map<String, Object> forwardAttributes;
    private Map<String, String[]> parameters;

    NettyForwardRequestWrapper(NettyDispatchFactory factory, HttpServletRequest original,
                        String targetPath, String queryString) {
        super(original);
        this.factory = factory;
        this.targetPath = targetPath;
        this.queryString = queryString;
        this.forwardAttributes = forwardAttributesOf(original);
    }

    // The delegate's value wins where it has one: on a nested forward that delegate is the previous
    // wrapper, so the outermost request's path elements survive any depth (Servlet 6.1 section 9.4.2).
    private static Map<String, Object> forwardAttributesOf(HttpServletRequest original) {
        if (original.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) != null) {
            return Collections.emptyMap();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(RequestDispatcher.FORWARD_REQUEST_URI, original.getRequestURI());
        attributes.put(RequestDispatcher.FORWARD_CONTEXT_PATH, original.getContextPath());
        attributes.put(RequestDispatcher.FORWARD_SERVLET_PATH, original.getServletPath());
        if (original.getQueryString() != null) {
            attributes.put(RequestDispatcher.FORWARD_QUERY_STRING, original.getQueryString());
        }
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public String getRequestURI() {
        return getContextPath() + targetPath;
    }

    @Override
    public String getServletPath() {
        return targetPath;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return factory.forRequestPath(targetPath, path);
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.FORWARD;
    }

    @Override
    public Object getAttribute(String name) {
        Object forwarded = forwardAttributes.get(name);
        return forwarded != null ? forwarded : super.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        if (forwardAttributes.isEmpty()) {
            return super.getAttributeNames();
        }
        Set<String> names = new LinkedHashSet<>(forwardAttributes.keySet());
        names.addAll(Collections.list(super.getAttributeNames()));
        return Collections.enumeration(names);
    }

    @Override
    public String getQueryString() {
        return queryString != null ? queryString : super.getQueryString();
    }

    @Override
    public String getParameter(String name) {
        String[] values = getParameterMap().get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = getParameterMap().get(name);
        return values == null ? null : values.clone();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(getParameterMap().keySet());
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (queryString == null) {
            return super.getParameterMap();
        }
        if (parameters == null) {
            parameters = mergedParameters();
        }
        return parameters;
    }

    // The dispatch path's parameters take precedence over the original's and are added to them, rather
    // than replacing them (Servlet 6.1 section 9.1.1).
    private Map<String, String[]> mergedParameters() {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        new QueryStringDecoder(queryString, StandardCharsets.UTF_8, false).parameters()
            .forEach((name, values) -> merged.put(name, new ArrayList<>(values)));
        super.getParameterMap().forEach((name, values) ->
            merged.computeIfAbsent(name, key -> new ArrayList<>()).addAll(List.of(values)));
        return NettyHttpServletRequest.toParameterMap(merged);
    }
}
