package io.github.azholdaspaev.nettyloomspring.autoconfigure.session;

import org.springframework.http.HttpHeaders;

import java.net.HttpCookie;
import java.util.List;

/**
 * Reads a named cookie back off a response, for tests that have to carry a session by hand --
 * {@code RestTestClient} does not persist cookies across exchanges.
 *
 * <p>Parsing goes through {@link HttpCookie}, the JDK's {@code Set-Cookie} parser, rather than a
 * substring-and-split in each test: the value under test is produced by
 * {@code ServerCookieEncoder.STRICT}, so asserting on it with an ad-hoc parser would mean two
 * hand-rolled cookie grammars in one package.
 */
final class SessionCookies {

    private SessionCookies() {
    }

    /** The value of {@code name} in the response's {@code Set-Cookie} headers, or {@code null}. */
    static String valueOf(HttpHeaders headers, String name) {
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
