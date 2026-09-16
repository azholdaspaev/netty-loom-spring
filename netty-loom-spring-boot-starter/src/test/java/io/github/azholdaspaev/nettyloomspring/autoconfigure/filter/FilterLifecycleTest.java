package io.github.azholdaspaev.nettyloomspring.autoconfigure.filter;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestFixtures.HeaderFilter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The teardown half of the filter lifecycle (issue #103), which needs the application lifecycle itself:
 * {@code Filter.destroy} must run on close, and the pair must stay balanced across the stop/start cycle
 * Spring replays on {@code ApplicationContext.start()}, {@code restart()} and CRaC restore.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FilterLifecycleTest {

    private static ConfigurableApplicationContext run() {
        return new SpringApplicationBuilder(FilterTestApplication.class)
            .properties("server.port=0")
            .run();
    }

    @Test
    void shouldDestroyFilterOnceOnClose() {
        HeaderFilter filter;
        try (ConfigurableApplicationContext context = run()) {
            filter = context.getBean(HeaderFilter.class);

            assertEquals(0, filter.destroyCount(), "nothing may be destroyed while the application is running");
        }

        assertEquals(1, filter.destroyCount(), "closing the application must destroy the filter exactly once");
    }

    @Test
    void shouldReinitializeFilterAcrossStopStartCycle() {
        try (ConfigurableApplicationContext context = run()) {
            HeaderFilter filter = context.getBean(HeaderFilter.class);

            context.stop();
            assertEquals(1, filter.destroyCount(), "stopping must destroy the filter");

            context.start();
            assertEquals(2, filter.initCount(),
                "a restarted context must re-initialize its filters rather than serve with them destroyed");
            assertEquals(1, filter.destroyCount());
        }
    }
}
