package io.github.azholdaspaev.nettyloomspring.autoconfigure.shutdown;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shutdown on the real Boot lifecycle. Spring Boot defaults {@code server.shutdown} to
 * {@code graceful}, so every application on this starter drains on context close. Draining used to
 * wait for open sockets, which an HTTP/1.1 client pools and holds open by design — so shutdown
 * burned the whole {@code server.netty.shutdown-grace-period} on a server with nothing in flight
 * (#67). A pooling {@link HttpClient} is essential there: it is what keeps the connection alive
 * after the response.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class GracefulShutdownTest {

    private static final long PROMPT_SHUTDOWN_MILLIS = 5_000;

    @Test
    void shouldShutDownPromptlyWhileClientHoldsIdleKeepAlive() throws Exception {
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

    @Test
    void shouldCutOffRequestOnceLifecyclePhaseTimeoutExpires() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
            SmokeNettyLoomApplication.class, HoldingController.class)
            .properties("server.port=0",
                "spring.lifecycle.timeout-per-shutdown-phase=1s",
                "server.netty.shutdown-grace-period=30s")
            .run();
        HoldingController holding = context.getBean(HoldingController.class);

        CompletableFuture<HttpResponse<String>> response;
        CompletableFuture<Long> settledAfterMillis;
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            response = client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hold")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertTrue(holding.entered.await(5, TimeUnit.SECONDS),
                "the request must be inside the controller before shutdown begins");

            long startedAt = System.nanoTime();
            settledAfterMillis = response.handle((_, _) -> (System.nanoTime() - startedAt) / 1_000_000L);
            context.close();
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }

        assertTrue(response.isCompletedExceptionally(),
            "once Spring's phase timeout expires, stop() must cut the request off rather than wait "
                + "out the grace period");
        assertTrue(settledAfterMillis.get() < HoldingController.HOLD_MILLIS,
            "the request must be cut off at the phase timeout, before the controller would have "
                + "answered; it settled after " + settledAfterMillis.get() + "ms");
    }

    @Test
    void shouldShutDownPromptlyWhileRequestNeverFinishes() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
            SmokeNettyLoomApplication.class, StuckController.class)
            .properties("server.port=0", "server.netty.shutdown-grace-period=1s")
            .run();
        StuckController stuck = context.getBean(StuckController.class);

        long elapsedMillis;
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/stuck")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertTrue(stuck.entered.await(5, TimeUnit.SECONDS),
                "the request must be inside the controller before shutdown begins");

            long startedAt = System.nanoTime();
            context.close();
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }

        assertTrue(elapsedMillis < PROMPT_SHUTDOWN_MILLIS,
            "closing the context must not wait for a dispatch that never finishes on its own, took "
                + elapsedMillis + "ms");
    }

    @RestController
    static class HoldingController {

        static final long HOLD_MILLIS = 3_000;

        final CountDownLatch entered = new CountDownLatch(1);

        @GetMapping("/hold")
        String hold() throws InterruptedException {
            entered.countDown();
            Thread.sleep(HOLD_MILLIS);
            return "released";
        }
    }

    @RestController
    static class StuckController {

        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch neverReleased = new CountDownLatch(1);

        @GetMapping("/stuck")
        String stuck() throws InterruptedException {
            entered.countDown();
            neverReleased.await();
            return "unreachable";
        }
    }
}
