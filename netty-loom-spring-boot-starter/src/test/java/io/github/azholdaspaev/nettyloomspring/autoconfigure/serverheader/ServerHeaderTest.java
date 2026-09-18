package io.github.azholdaspaev.nettyloomspring.autoconfigure.serverheader;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code server.server-header} is bound onto the factory by Boot and must reach the wire (issue #167).
 * Read over a raw socket because the header must also be on the responses the pipeline writes without
 * the application -- a 414 for an over-long request line here -- which no client library distinguishes.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServerHeaderTest {

    @Test
    void shouldStampConfiguredServerHeaderOnEveryResponse() throws IOException {
        try (var context = run("server.server-header=MyApp")) {
            int port = port(context);

            try (Socket socket = RawHttpClient.connect(port, Duration.ofSeconds(10))) {
                RawHttpResponse response = exchange(socket, "GET /get HTTP/1.1", "Host: localhost");
                assertEquals(200, response.status());
                assertEquals("MyApp", response.header("server"),
                    "a response the application produced must carry the configured Server header");
            }
            try (Socket socket = RawHttpClient.connect(port, Duration.ofSeconds(10))) {
                RawHttpResponse response = exchange(socket, "GET /" + "a".repeat(11_000) + " HTTP/1.1", "Host: localhost");
                assertEquals(414, response.status());
                assertEquals("MyApp", response.header("server"),
                    "a rejection the pipeline wrote without the application must carry it too");
            }
        }
    }

    @Test
    void shouldWriteNoServerHeaderWhenPropertyIsUnset() throws IOException {
        try (var context = run()) {
            try (Socket socket = RawHttpClient.connect(port(context), Duration.ofSeconds(10))) {
                RawHttpResponse response = exchange(socket, "GET /get HTTP/1.1", "Host: localhost");
                assertEquals(200, response.status());
                assertNull(response.header("server"),
                    "with no server.server-header the container must not advertise itself");
            }
        }
    }

    @Test
    void shouldFailStartupWhenServerHeaderHasLineBreak() {
        assertThrows(RuntimeException.class, () -> run("server.server-header=My\nApp").close(),
            "a value no response head can carry must fail startup, not every response");
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private static RawHttpResponse exchange(Socket socket, String requestLine, String... headers) throws IOException {
        RawHttpClient.send(socket, requestLine, headers);
        RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
        response.readBody();
        return response;
    }
}
