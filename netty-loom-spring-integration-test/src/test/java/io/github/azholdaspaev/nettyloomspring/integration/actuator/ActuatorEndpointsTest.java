package io.github.azholdaspaev.nettyloomspring.integration.actuator;

import io.github.azholdaspaev.nettyloomspring.integration.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActuatorEndpointsTest extends BaseIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness", "/actuator/metrics", "/actuator/prometheus"})
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldServeEndpointOnServerPort(String path) {
        restTestClient.get().uri(path)
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldExposeNettyServerGaugesInPrometheus() {
        String scrape = restTestClient.get().uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();

        assertNotNull(scrape, "the prometheus endpoint must answer with a body");
        assertTrue(scrape.contains("netty_server_connections_active_connections"),
            "the connection gauge must reach the Prometheus registry; got " + scrape);
        assertTrue(scrape.contains("netty_server_dispatches_active_tasks"),
            "the dispatch gauge must reach the Prometheus registry; got " + scrape);
    }
}
