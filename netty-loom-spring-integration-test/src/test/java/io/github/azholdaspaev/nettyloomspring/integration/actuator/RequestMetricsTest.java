package io.github.azholdaspaev.nettyloomspring.integration.actuator;

import io.github.azholdaspaev.nettyloomspring.integration.support.BaseIntegrationTest;
import io.github.azholdaspaev.nettyloomspring.integration.web.ItemUnavailableException;
import io.github.azholdaspaev.nettyloomspring.integration.web.RejectingFilterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestMetricsTest extends BaseIntegrationTest {

    private static final String REQUESTS = "http.server.requests";

    private static final String ITEM_URI = "/items/{id}";

    private static final String UNAVAILABLE_ITEM_URI = "/items/{id}/unavailable";

    private static final Duration RECORDING_DEADLINE = Duration.ofSeconds(5);

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldTagRequestWithRouteTemplateAsUri() {
        long before = countOf(ITEM_URI, "200", "none");

        restTestClient.get().uri("/items/42").exchange().expectStatus().isOk();

        /*
         * Awaited, not read at once: the bridge flushes the body to the wire before the observation
         * filter's finally block stops the timer, so the client can return first.
         */
        await().atMost(RECORDING_DEADLINE).untilAsserted(() ->
            assertEquals(before + 1, countOf(ITEM_URI, "200", "none"),
                "the request must be recorded under its route template, not its path"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRecordOneSampleForFailureAcrossErrorDispatch() {
        String exception = ItemUnavailableException.class.getSimpleName();
        long before = countOf(UNAVAILABLE_ITEM_URI, "500", exception);
        long beforeEitherDispatch = countByUri(Set.of(UNAVAILABLE_ITEM_URI, "/error"));

        restTestClient.get().uri("/items/7/unavailable").exchange().expectStatus().isEqualTo(500);

        await().atMost(RECORDING_DEADLINE).untilAsserted(() ->
            assertEquals(before + 1, countOf(UNAVAILABLE_ITEM_URI, "500", exception),
                "the failed request must be recorded with status 500 and the controller's exception"));
        assertEquals(beforeEitherDispatch + 1, countByUri(Set.of(UNAVAILABLE_ITEM_URI, "/error")),
            "the error-page dispatch must not record a second sample under either uri");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRecordFilterFailureUnderAnsweredStatus() {
        String exception = IllegalArgumentException.class.getSimpleName();
        long before = countOf("UNKNOWN", "500", exception);

        restTestClient.get().uri(RejectingFilterConfig.REJECTED_PATH).exchange().expectStatus().isEqualTo(500);

        await().atMost(RECORDING_DEADLINE).untilAsserted(() ->
            assertEquals(before + 1, countOf("UNKNOWN", "500", exception),
                "the status recorded for a filter's failure must be the status the client received"));
    }

    private long countOf(String uri, String status, String exception) {
        return meterRegistry.find(REQUESTS).tags("uri", uri, "status", status, "exception", exception)
            .timers().stream().mapToLong(Timer::count).sum();
    }

    private long countByUri(Set<String> uris) {
        return meterRegistry.find(REQUESTS).tag("uri", uris::contains)
            .timers().stream().mapToLong(Timer::count).sum();
    }
}
