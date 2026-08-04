package io.github.azholdaspaev.nettyloomspring.autoconfigure.samesite.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Isolated test application for the {@code CookieSameSiteSupplier} integration tests. Kept in its own
 * package so its supplier beans do not bleed into the {@code cookie.app} scan (and vice versa).
 */
@SpringBootApplication
public class SameSiteTestApplication {
}
