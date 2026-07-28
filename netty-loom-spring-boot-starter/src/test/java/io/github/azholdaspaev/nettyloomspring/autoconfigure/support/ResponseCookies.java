package io.github.azholdaspaev.nettyloomspring.autoconfigure.support;

import org.springframework.http.HttpHeaders;

import java.net.HttpCookie;
import java.util.List;

/**
 * Reads a named cookie back off a response, for tests that have to carry one by hand -- {@code
 * RestTestClient} does not persist cookies across exchanges.
 *
 * <p>Lives beside {@code ThrowableChains} rather than in a feature package: nothing about it is
 * session-specific, and the alternative is the next package that needs it hand-rolling a third parser.
 *
 * <p>Parsing goes through {@link HttpCookie}, the JDK's {@code Set-Cookie} parser, rather than a
 * substring-and-split in each test: the value under test is produced by
 * {@code ServerCookieEncoder.STRICT}, so asserting on it with an ad-hoc parser would mean two
 * hand-rolled cookie grammars in one package.
 */
public final class ResponseCookies {

    private ResponseCookies() {
    }

    /** The value of {@code name} in the response's {@code Set-Cookie} headers, or {@code null}. */
    public static String valueOf(HttpHeaders headers, String name) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        return setCookies.stream()
            .flatMap(header -> HttpCookie.parse(header).stream())
            .filter(cookie -> cookie.getName().equals(name))
            .map(HttpCookie::getValue)
            .findFirst()
            .orElse(null);
    }
}
