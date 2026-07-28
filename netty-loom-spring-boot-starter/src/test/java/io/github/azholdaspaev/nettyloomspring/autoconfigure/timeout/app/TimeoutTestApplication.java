package io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Isolated test application for the read-timeout tests. Kept in its own package so its deliberately
 * slow fixture controller does not bleed into the {@code smoke.app} component scan (and vice versa).
 */
@SpringBootApplication
public class TimeoutTestApplication {
}
