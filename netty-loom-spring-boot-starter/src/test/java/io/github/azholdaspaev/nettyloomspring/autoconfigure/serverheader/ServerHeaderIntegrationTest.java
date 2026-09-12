package io.github.azholdaspaev.nettyloomspring.autoconfigure.serverheader;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code server.server-header} is bound onto the factory by Boot and must reach the wire (issue #167).
 * Read over a raw socket because the header must also be on the responses the pipeline writes without
 * the application — a 414 for an over-long request line here — which no client library distinguishes.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServerHeaderIntegrationTest {

    @Test
    void shouldStampTheConfiguredServerHeaderOnEveryResponse() throws IOException {
        try (ConfigurableApplicationContext context = start("server.server-header=MyApp")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();

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
    void shouldWriteNoServerHeaderWhenThePropertyIsUnset() throws IOException {
        try (ConfigurableApplicationContext context = start()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();

            try (Socket socket = RawHttpClient.connect(port, Duration.ofSeconds(10))) {
                RawHttpResponse response = exchange(socket, "GET /get HTTP/1.1", "Host: localhost");
                assertEquals(200, response.status());
                assertNull(response.header("server"),
                    "with no server.server-header the container must not advertise itself");
            }
        }
    }

    private static ConfigurableApplicationContext start(String... properties) {
        return new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
            .properties("server.port=0")
            .properties(properties)
            .run();
    }

    private static RawHttpResponse exchange(Socket socket, String requestLine, String... headers) throws IOException {
        RawHttpClient.send(socket, requestLine, headers);
        RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
        response.readBody();
        return response;
    }
}
