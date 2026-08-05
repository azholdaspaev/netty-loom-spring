package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.http.Cookie;

/**
 * A container-wide {@code SameSite} policy for cookies the application writes (issue #85).
 *
 * <p>Expressed in Jakarta terms rather than as Boot's {@code CookieSameSiteSupplier} because this
 * module carries no Spring Boot dependency; the starter adapts the supplier beans to it.
 */
@FunctionalInterface
public interface NettyCookieSameSiteResolver {

    /**
     * The absence of a container-wide policy: every cookie keeps whatever {@code SameSite} it was
     * given, and one that declares none is emitted without the attribute. Deliberately not named
     * {@code NONE}, which is a real and permissive {@code SameSite} value that this resolver never
     * emits, nor {@code OMITTED}, which would overstate it -- a cookie declaring {@code SameSite}
     * explicitly still carries it under this policy.
     */
    NettyCookieSameSiteResolver NO_OPINION = cookie -> null;

    /** The {@code SameSite} attribute value for {@code cookie}, or {@code null} for no opinion. */
    String resolve(Cookie cookie);
}
