package io.github.azholdaspaev.nettyloomspring.autoconfigure.sizelimit;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.requestbody.app.RequestBodyGate;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.requestbody.app.RequestBodyTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every limit here is set far below its default, so a request that the default would accept is the
 * one refused: a pass proves the property reached the pipeline, not that a limit exists.
 */
@SpringBootTest(
    classes = RequestBodyTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.netty.max-http-body-size=100B",
        "server.netty.max-header-size=200B",
        "server.netty.max-initial-line-length=100B",
        "server.netty.max-chunk-size=" + HttpSizeLimitBindingTest.MAX_CHUNK_BYTES + "B"
    }
)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class HttpSizeLimitBindingTest {

    static final int MAX_CHUNK_BYTES = 5;

    @LocalServerPort
    int port;

    @Autowired
    RequestBodyGate gate;

    @Test
    void shouldRefuseBodyOverConfiguredLimitWith413() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1", "Host: localhost", "Content-Length: 101");
            RawHttpClient.sendBody(socket, "x".repeat(101));

            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(413, response.status(),
                "a 101-byte body must be refused under server.netty.max-http-body-size=100B");
        }
    }

    @Test
    void shouldRefuseHeaderOverConfiguredLimitWith431() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1", "Host: localhost",
                "X-Big: " + "h".repeat(300), "Content-Length: 0");

            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(431, response.status(),
                "a 300-byte header must be refused under server.netty.max-header-size=200B");
        }
    }

    @Test
    void shouldRefuseInitialLineOverConfiguredLimitWith414() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/" + "u".repeat(150) + " HTTP/1.1", "Host: localhost",
                "Content-Length: 0");

            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(414, response.status(),
                "a 150-byte request line must be refused under server.netty.max-initial-line-length=100B");
        }
    }

    @Test
    void shouldHandBodyToHandlerInConfiguredChunkSize() throws Exception {
        try (Socket socket = connect()) {
            String body = "hello world!";
            RawHttpClient.send(socket, "POST /upload/gated HTTP/1.1", "Host: localhost",
                "Content-Length: " + body.length());
            RawHttpClient.sendBody(socket, body);

            for (int total = 0; total < body.length(); ) {
                int read = gate.awaitRead();
                assertTrue(read <= MAX_CHUNK_BYTES,
                    "one read spans at most one decoded chunk, so a " + read + "-byte read means the "
                        + "codec ignored server.netty.max-chunk-size=" + MAX_CHUNK_BYTES + "B");
                total += read;
            }

            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(200, response.status());
            assertEquals("read " + body.length(), response.readBody());
        }
    }

    private Socket connect() throws IOException {
        return RawHttpClient.connect(port, Duration.ofSeconds(15));
    }
}
