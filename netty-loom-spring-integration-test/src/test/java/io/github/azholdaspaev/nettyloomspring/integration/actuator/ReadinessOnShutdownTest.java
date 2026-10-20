package io.github.azholdaspaev.nettyloomspring.integration.actuator;

import io.github.azholdaspaev.nettyloomspring.integration.app.RestServiceApplication;
import io.github.azholdaspaev.nettyloomspring.integration.support.JwkSetServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadinessOnShutdownTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldReportOutOfServiceBeforeDrainBegins() throws Exception {
        /*
         * ContextClosedEvent rather than the AvailabilityChangeEvent itself: Boot's doClose() has
         * multicast REFUSING_TRAFFIC to every listener by then, ApplicationAvailabilityBean included,
         * and has not yet stopped the lifecycle beans whose graceful phase begins the drain. A listener
         * on the availability event is unordered against that bean and could still read UP.
         */
        try (HttpClient client = HttpClient.newHttpClient()) {
            ReadinessProbe probe = new ReadinessProbe(client);
            ConfigurableApplicationContext context = new SpringApplicationBuilder(RestServiceApplication.class)
                .properties("server.port=0",
                    JwkSetServer.JWK_SET_URI_PROPERTY + "=" + JwkSetServer.getInstance().getJwkSetUri())
                .listeners(probe)
                .run();
            context.close();

            assertTrue(probe.response.isDone(), "the probe must run while the context closes");
            HttpResponse<String> response = probe.response.get();
            assertEquals(503, response.statusCode(), "a closing context must fail its readiness probe");
            assertTrue(response.body().contains("OUT_OF_SERVICE"),
                "readiness must report OUT_OF_SERVICE; got " + response.body());
        }
    }

    private static final class ReadinessProbe implements ApplicationListener<ContextClosedEvent> {

        private final HttpClient client;

        private final CompletableFuture<HttpResponse<String>> response = new CompletableFuture<>();

        private ReadinessProbe(HttpClient client) {
            this.client = client;
        }

        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            int port = ((WebServerApplicationContext) event.getApplicationContext()).getWebServer().getPort();
            HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/health/readiness")).build();
            try {
                response.complete(client.send(request, HttpResponse.BodyHandlers.ofString()));
            } catch (Exception failure) {
                response.completeExceptionally(failure);
            }
        }
    }
}
