package io.github.azholdaspaev.nettyloomspring.autoconfigure.shutdown;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #67 regression gate. Spring Boot defaults {@code server.shutdown} to {@code graceful}, so
 * every application on this starter drains on context close. Draining used to wait for open sockets,
 * which an HTTP/1.1 client pools and holds open by design — so shutdown burned the whole
 * {@code server.netty.shutdown-grace-period} (30s by default) on a server with nothing in flight.
 *
 * <p>A pooling {@link HttpClient} is essential here: it is what keeps the connection alive after the
 * response, which is precisely the condition that used to hang.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class GracefulShutdownTest {

    private static final long PROMPT_SHUTDOWN_MILLIS = 5_000;

    @Test
    void shouldShutDownPromptlyWhileAClientHoldsAnIdleKeepAliveConnection() throws Exception {
        // Held for the lifetime of the test so the pooled connection stays open across the shutdown.
        HttpClient pooling = HttpClient.newHttpClient();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
            .properties("server.port=0")
            .run();

        long elapsedMillis;
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpResponse<String> response = pooling.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/get")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "the app must serve a request before we test its shutdown");

            long startedAt = System.nanoTime();
            context.close();
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }

        assertTrue(elapsedMillis < PROMPT_SHUTDOWN_MILLIS,
            "graceful shutdown must not wait out the grace period for an idle pooled connection, took "
                + elapsedMillis + "ms");
    }
}
