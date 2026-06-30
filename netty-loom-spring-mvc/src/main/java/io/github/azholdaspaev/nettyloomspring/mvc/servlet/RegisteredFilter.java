package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import java.util.EnumSet;
import java.util.Set;

/**
 * An executable filter registration: the live {@link Filter} instance together with the
 * URL patterns and dispatcher types it was mapped to. Produced by
 * {@link NettyServletContext#getRegisteredFilters()} and consumed by {@link NettyFilterChain}.
 *
 * <p>Matching is servlet-spec URL-pattern matching (Servlet 6, §12.2), not Ant matching.
 */
public record RegisteredFilter(String name, Filter filter, Set<String> urlPatterns,
                               EnumSet<DispatcherType> dispatcherTypes) {

    /**
     * Returns {@code true} if this filter applies to a {@code dispatchType} dispatch of
     * {@code requestPath} — i.e. the filter is mapped for that dispatcher type and one of its
     * URL patterns matches the path.
     */
    public boolean matches(String requestPath, DispatcherType dispatchType) {
        if (!dispatcherTypes.contains(dispatchType)) {
            return false;
        }
        for (String pattern : urlPatterns) {
            if (patternMatches(pattern, requestPath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean patternMatches(String pattern, String path) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        // Match-all.
        if (pattern.equals("/*")) {
            return true;
        }
        // Path-prefix mapping: "/p/*" matches "/p", "/p/", and "/p/...", but not "/px".
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        // Extension mapping: "*.ext" (a bare "*." has no extension and matches nothing).
        if (pattern.startsWith("*.") && pattern.length() > 2) {
            String extension = pattern.substring(1); // ".ext"
            return path.endsWith(extension);
        }
        // Exact mapping.
        return pattern.equals(path);
    }
}
