package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.http.Cookie;

/**
 * A container-wide {@code SameSite} policy for cookies the application writes (issue #85).
 *
 * <p>Expressed in Jakarta terms rather than as Boot's {@code CookieSameSiteSupplier} because this
 * module carries no Spring Boot dependency; the starter adapts the supplier beans to it.
 */
@FunctionalInterface
public interface CookieSameSiteResolver {

    CookieSameSiteResolver NONE = cookie -> null;

    /** The {@code SameSite} attribute value for {@code cookie}, or {@code null} for no opinion. */
    String resolve(Cookie cookie);
}
