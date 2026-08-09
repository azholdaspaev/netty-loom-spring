package io.github.azholdaspaev.nettyloomspring.autoconfigure.cookie.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Each feature's test application is kept in its own package so that its fixture beans do not bleed
 * into another package's component scan.
 */
@SpringBootApplication
public class CookieTestApplication {
}
