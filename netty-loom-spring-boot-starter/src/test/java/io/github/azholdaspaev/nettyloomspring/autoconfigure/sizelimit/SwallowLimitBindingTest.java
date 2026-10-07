package io.github.azholdaspaev.nettyloomspring.autoconfigure.sizelimit;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.requestbody.app.RequestBodyTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both bounds sit on the far side of their defaults, so each test fails if the property never
 * reached the pipeline.
 */
@SpringBootTest(
    classes = RequestBodyTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.netty.max-http-body-size=100B",
        "server.netty.max-swallow-size=16MB",
        "server.netty.swallow-timeout=1s"
    }
)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class SwallowLimitBindingTest {

    private static final int REFUSED_BODY_BYTES = 8 * 1024 * 1024;

    private static final String PIECE = "x".repeat(64 * 1024);

    private static final Duration DEFAULT_SWALLOW_TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    @Test
    void shouldSwallowRefusedBodyUpToConfiguredLimit() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1",
                "Host: localhost", "Content-Length: " + REFUSED_BODY_BYTES);
            for (int sent = 0; sent < REFUSED_BODY_BYTES; sent += PIECE.length()) {
                RawHttpClient.sendBody(socket, PIECE);
            }

            assertEquals(413, RawHttpResponse.read(socket.getInputStream()).status(),
                "an 8MB body is past the default swallow limit but within server.netty.max-swallow-size=16MB");
        }
    }

    @Test
    void shouldCloseOnceConfiguredSwallowTimeoutElapses() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1",
                "Host: localhost", "Content-Length: " + Integer.MAX_VALUE);
            assertEquals(413, RawHttpResponse.read(socket.getInputStream()).status());
            long refusedAt = System.nanoTime();

            assertThrows(IOException.class, () -> {
                while (true) {
                    RawHttpClient.sendBody(socket, "x");
                    Thread.sleep(100);
                }
            });

            Duration held = Duration.ofNanos(System.nanoTime() - refusedAt);
            assertTrue(held.compareTo(DEFAULT_SWALLOW_TIMEOUT) < 0,
                "held for " + held + ", so server.netty.swallow-timeout=1s did not apply");
        }
    }

    private Socket connect() throws IOException {
        return RawHttpClient.connect(port, Duration.ofSeconds(15));
    }
}
