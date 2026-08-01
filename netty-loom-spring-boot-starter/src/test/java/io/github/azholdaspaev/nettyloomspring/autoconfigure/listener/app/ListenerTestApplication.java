package io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Isolated test application for the servlet listener integration tests. Kept in its own package so its
 * fixture controller and listener do not bleed into another package's component scan (and vice versa).
 */
@SpringBootApplication
public class ListenerTestApplication {
}
