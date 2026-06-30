package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisteredFilterTest {

    private static RegisteredFilter filterFor(Set<String> patterns, EnumSet<DispatcherType> dispatcherTypes) {
        Filter noop = (request, response, chain) -> chain.doFilter(request, response);
        return new RegisteredFilter("f", noop, patterns, dispatcherTypes);
    }

    private static RegisteredFilter requestFilter(String... patterns) {
        return filterFor(new LinkedHashSet<>(Set.of(patterns)), EnumSet.of(DispatcherType.REQUEST));
    }

    private static boolean matchesAsRequest(RegisteredFilter filter, String path) {
        return filter.matches(path, DispatcherType.REQUEST);
    }

    @Test
    void exactPatternMatchesOnlyExactPath() {
        var filter = requestFilter("/foo");

        assertTrue(matchesAsRequest(filter,"/foo"));
        assertFalse(matchesAsRequest(filter,"/foo/bar"));
        assertFalse(matchesAsRequest(filter,"/foobar"));
    }

    @Test
    void extensionPatternMatchesBySuffix() {
        var filter = requestFilter("*.json");

        assertTrue(matchesAsRequest(filter,"/api/data.json"));
        assertFalse(matchesAsRequest(filter,"/api/data.xml"));
    }

    @Test
    void wildcardPatternMatchesEverything() {
        var filter = requestFilter("/*");

        assertTrue(matchesAsRequest(filter,"/"));
        assertTrue(matchesAsRequest(filter,"/anything/at/all"));
    }

    @Test
    void pathPrefixMatchesBarePrefixAndDescendantsButNotSiblingPrefix() {
        var filter = requestFilter("/api/*");

        assertTrue(matchesAsRequest(filter,"/api"));
        assertTrue(matchesAsRequest(filter,"/api/"));
        assertTrue(matchesAsRequest(filter,"/api/users"));
        assertFalse(matchesAsRequest(filter,"/apix"));
        assertFalse(matchesAsRequest(filter,"/other"));
    }

    @Test
    void degenerateExtensionPatternMatchesNothing() {
        var filter = requestFilter("*.");

        assertFalse(matchesAsRequest(filter,"/file."));
        assertFalse(matchesAsRequest(filter,"/file.json"));
    }

    @Test
    void multiplePatternsMatchIfAnyMatches() {
        var filter = requestFilter("/never", "/api/*");

        assertTrue(matchesAsRequest(filter,"/api/users"));
        assertFalse(matchesAsRequest(filter,"/elsewhere"));
    }

    @Test
    void doesNotMatchWhenDispatcherTypesLacksRequest() {
        var filter = filterFor(Set.of("/*"), EnumSet.of(DispatcherType.ASYNC));

        assertFalse(matchesAsRequest(filter,"/anything"));
    }
}
