package io.github.azholdaspaev.nettyloomspring.autoconfigure.port;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.ThrowableChains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.TestSocketUtils;

import java.net.BindException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #25 compat gate: the standard {@code server.port} must bind the Netty server to the exact
 * chosen port. A fixed port is required (not {@code RANDOM_PORT}, which forces {@code server.port=0}
 * and can never observe a specific value), so a free port has to be picked in advance; that
 * pick-then-rebind carries an inherent TOCTOU window, which the retry loop absorbs. The timeout is
 * derived from {@link #MAX_ATTEMPTS} rather than written as a literal so a run that exhausts every
 * attempt still fits inside it, keeping the anti-flake mechanism reachable (issue #68).
 */
class ServerPortBindingTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final int SECONDS_PER_ATTEMPT = 5;

    @Test
    @Timeout(value = MAX_ATTEMPTS * SECONDS_PER_ATTEMPT, unit = TimeUnit.SECONDS)
    void shouldBindStandardServerPort() throws Exception {
        BindException lastBindFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            int chosenPort = TestSocketUtils.findAvailableTcpPort();
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
                .properties("server.port=" + chosenPort)
                .run();
                 HttpClient client = HttpClient.newHttpClient()) {

                int boundPort = ((WebServerApplicationContext) context).getWebServer().getPort();
                assertEquals(chosenPort, boundPort);

                HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + chosenPort + "/get")).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                return;
            } catch (RuntimeException e) {
                BindException bindFailure = ThrowableChains.findInChain(e, BindException.class);
                if (bindFailure == null) {
                    throw e;
                }
                // The chosen port was taken between selection and bind; retry with a fresh one.
                lastBindFailure = bindFailure;
            }
        }
        throw new AssertionError("server.port binding lost the race " + MAX_ATTEMPTS + " times", lastBindFailure);
    }
}
