package io.github.azholdaspaev.nettyloomspring.autoconfigure.support;

import org.springframework.http.HttpHeaders;

import java.net.HttpCookie;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads a named cookie back off a response: its value, for tests that have to carry one by hand
 * ({@code RestTestClient} does not persist cookies across exchanges), or its raw {@code Set-Cookie}
 * line, for tests asserting on attributes. Parsing goes through {@link HttpCookie}, the JDK's
 * {@code Set-Cookie} parser, rather than a substring-and-split per test: the value under test is
 * produced by {@code ServerCookieEncoder.STRICT}.
 */
public final class ResponseCookies {

    private ResponseCookies() {
    }

    public static String valueOf(HttpHeaders headers, String name) {
        return lines(headers)
            .flatMap(header -> HttpCookie.parse(header).stream())
            .filter(cookie -> cookie.getName().equals(name))
            .map(HttpCookie::getValue)
            .findFirst()
            .orElse(null);
    }

    /**
     * Attributes have to be read off the raw line: {@link HttpCookie} models no {@code SameSite}.
     */
    public static String lineFor(HttpHeaders headers, String name) {
        return lines(headers)
            .filter(header -> HttpCookie.parse(header).stream()
                .anyMatch(cookie -> cookie.getName().equals(name)))
            .findFirst()
            .orElse(null);
    }

    private static Stream<String> lines(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        return setCookies == null ? Stream.empty() : setCookies.stream();
    }
}
