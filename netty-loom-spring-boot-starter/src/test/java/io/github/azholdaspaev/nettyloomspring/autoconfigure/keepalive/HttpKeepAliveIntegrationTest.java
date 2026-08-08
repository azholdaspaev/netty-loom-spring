package io.github.azholdaspaev.nettyloomspring.autoconfigure.keepalive;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Keep-alive lives on the wire: {@code Connection} headers and socket lifetime are exactly what
 * RestTestClient and java.net.http.HttpClient hide, so these tests speak HTTP over a raw socket.
 */
@SpringBootTest(
    classes = SmokeNettyLoomApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class HttpKeepAliveIntegrationTest {

    private static final String PATH = "/api/greeting";

    @LocalServerPort
    int port;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReuseConnectionForSequentialHttp11Requests() throws Exception {
        try (Socket socket = connect()) {
            RawHttpResponse first = exchange(socket, "GET " + PATH + " HTTP/1.1", "Host: localhost");
            RawHttpResponse second = exchange(socket, "GET " + PATH + " HTTP/1.1", "Host: localhost");

            assertEquals(200, first.status(), "first request on a fresh connection should succeed");
            assertNull(first.header("connection"),
                "HTTP/1.1 keep-alive is the default and needs no Connection header");
            assertEquals(200, second.status(), "the same connection must serve a second request");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCloseConnectionWhenClientRequestsClose() throws Exception {
        try (Socket socket = connect()) {
            RawHttpResponse response = exchange(socket, "GET " + PATH + " HTTP/1.1",
                "Host: localhost", "Connection: close");

            assertEquals(200, response.status());
            assertEquals("close", response.header("connection"),
                "a requested close must be echoed on the response");
            assertEquals(-1, socket.getInputStream().read(),
                "server must close the connection (EOF) after honouring Connection: close");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCloseConnectionForHttp10WithoutKeepAlive() throws Exception {
        try (Socket socket = connect()) {
            RawHttpResponse response = exchange(socket, "GET " + PATH + " HTTP/1.0");

            assertEquals(200, response.status());
            assertEquals("close", response.header("connection"),
                "HTTP/1.0 defaults to close, which must be spelled out on the response");
            assertEquals(-1, socket.getInputStream().read(),
                "server must close the connection (EOF) after an HTTP/1.0 request without keep-alive");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReuseConnectionForHttp10WithKeepAlive() throws Exception {
        try (Socket socket = connect()) {
            RawHttpResponse first = exchange(socket, "GET " + PATH + " HTTP/1.0", "Connection: keep-alive");

            assertEquals(200, first.status());
            assertEquals("keep-alive", first.header("connection"),
                "an HTTP/1.0 client only reuses the connection when keep-alive is spelled out");

            RawHttpResponse second = exchange(socket, "GET " + PATH + " HTTP/1.0", "Connection: keep-alive");
            assertEquals(200, second.status(), "the same connection must serve a second HTTP/1.0 request");
        }
    }

    private Socket connect() throws IOException {
        return RawHttpClient.connect(port, Duration.ofSeconds(5));
    }

    private static RawHttpResponse exchange(Socket socket, String requestLine, String... headers) throws IOException {
        RawHttpClient.send(socket, requestLine, headers);
        RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
        // Drained here so a reused socket starts the next read on a status line, not leftover bytes.
        response.readBody();
        return response;
    }
}
