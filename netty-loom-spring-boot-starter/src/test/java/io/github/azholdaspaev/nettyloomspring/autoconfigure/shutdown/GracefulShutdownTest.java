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
 * {@code graceful}, so an application that does not opt into {@code immediate} drains on context
 * close. Draining used to wait for open sockets, which an HTTP/1.1 client pools and holds open by
 * design — so shutdown burned the whole {@code server.netty.shutdown-grace-period} on a server
 * with nothing in flight (#67). A pooling {@link HttpClient} is essential there: it is what keeps
 * the connection alive after the response.
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
        HeldRequest held = holdRequestAcrossClose(
            "spring.lifecycle.timeout-per-shutdown-phase=1s",
            "server.netty.shutdown-grace-period=30s");

        assertTrue(held.response().isCompletedExceptionally(),
            "once Spring's phase timeout expires, stop() must cut the request off rather than wait "
                + "out the grace period");
        assertTrue(held.settledAfterMillis() < HoldingController.HOLD_MILLIS,
            "the request must be cut off at the phase timeout, before the controller would have "
                + "answered; it settled after " + held.settledAfterMillis() + "ms");
    }

    @Test
    void shouldDrainRequestToCompletionWhenShutdownIsGraceful() throws Exception {
        HeldRequest held = holdRequestAcrossClose(
            "server.shutdown=graceful",
            "spring.lifecycle.timeout-per-shutdown-phase=30s",
            "server.netty.shutdown-grace-period=30s");

        assertEquals("released", held.response().get(5, TimeUnit.SECONDS).body(),
            "under server.shutdown=graceful the in-flight request must be drained to its response");
    }

    @Test
    void shouldCutOffRequestWithoutDrainingWhenShutdownIsImmediate() throws Exception {
        HeldRequest held = holdRequestAcrossClose(
            "server.shutdown=immediate",
            "spring.lifecycle.timeout-per-shutdown-phase=30s",
            "server.netty.shutdown-grace-period=30s");

        assertTrue(held.response().isCompletedExceptionally(),
            "under server.shutdown=immediate, stop() must cut the request off rather than drain it");
        assertTrue(held.settledAfterMillis() < HoldingController.HOLD_MILLIS,
            "the request must be cut off without waiting for the controller to answer; it settled after "
                + held.settledAfterMillis() + "ms");
    }

    private static HeldRequest holdRequestAcrossClose(String... properties) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
            SmokeNettyLoomApplication.class, HoldingController.class)
            .properties("server.port=0")
            .properties(properties)
            .run();
        HoldingController holding = context.getBean(HoldingController.class);

        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            CompletableFuture<HttpResponse<String>> response = client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hold")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertTrue(holding.entered.await(5, TimeUnit.SECONDS),
                "the request must be inside the controller before shutdown begins");

            long startedAt = System.nanoTime();
            CompletableFuture<Long> settledAfterMillis =
                response.handle((_, _) -> (System.nanoTime() - startedAt) / 1_000_000L);
            context.close();
            return new HeldRequest(response, settledAfterMillis.get());
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }
    }

    private record HeldRequest(CompletableFuture<HttpResponse<String>> response, long settledAfterMillis) {
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
        assertTrue(stuck.interrupted.await(5, TimeUnit.SECONDS),
            "destroying the dispatch executor must interrupt the stuck dispatch, not just abandon it");
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
        final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch neverReleased = new CountDownLatch(1);

        @GetMapping("/stuck")
        String stuck() throws InterruptedException {
            entered.countDown();
            try {
                neverReleased.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return "unreachable";
        }
    }
}
