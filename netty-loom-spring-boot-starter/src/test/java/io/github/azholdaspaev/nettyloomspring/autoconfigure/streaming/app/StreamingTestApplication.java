package io.github.azholdaspaev.nettyloomspring.autoconfigure.streaming.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Isolated test application for the streaming integration tests. Kept in its own package so its
 * fixture controller does not bleed into the {@code smoke.app} component scan (and vice versa).
 */
@SpringBootApplication
public class StreamingTestApplication {
}
