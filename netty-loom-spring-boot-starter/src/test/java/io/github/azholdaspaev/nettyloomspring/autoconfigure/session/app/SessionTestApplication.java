package io.github.azholdaspaev.nettyloomspring.autoconfigure.session.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Isolated test application for the session integration tests. Kept in its own package so its
 * fixture controller does not bleed into the {@code smoke.app} component scan (and vice versa).
 */
@SpringBootApplication
public class SessionTestApplication {
}
