package io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Isolated test application for the filter-chain integration tests. Kept in its own package so
 * its fixture filters do not bleed into the {@code smoke.app} component scan (and vice versa).
 */
@SpringBootApplication
public class FilterTestApplication {
}
