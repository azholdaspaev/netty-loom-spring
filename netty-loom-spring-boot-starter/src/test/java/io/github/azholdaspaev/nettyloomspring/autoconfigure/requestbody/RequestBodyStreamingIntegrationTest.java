package io.github.azholdaspaev.nettyloomspring.autoconfigure.requestbody;

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
 * The request-side mirror of {@code StreamingIntegrationTest}: whether a body streams is a property of
 * the wire, and a server that buffered every request whole would satisfy any assertion made on the
 * finished response. So these speak HTTP over a raw socket and hand the body over a piece at a time.
 */
@SpringBootTest(
    classes = RequestBodyTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RequestBodyStreamingIntegrationTest {

    /** Several times the connection's queue bound, so the body cannot be resident all at once. */
    private static final int LARGE_BODY_BYTES = 256 * 1024;

    /** Past any limit the server could be configured with, so nothing here restates that limit. */
    private static final long UNACCEPTABLE_CONTENT_LENGTH = 100L * 1024 * 1024;

    @LocalServerPort
    int port;

    @Autowired
    RequestBodyGate gate;

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldHandTheHandlerEachChunkBeforeTheNextIsSent() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/gated HTTP/1.1",
                "Host: localhost", "Transfer-Encoding: chunked");

            RawHttpClient.sendChunk(socket, "first");
            assertEquals(5, gate.awaitRead(), "the handler must see chunk one before chunk two is sent");
            RawHttpClient.sendChunk(socket, "second!");
            assertEquals(7, gate.awaitRead());
            RawHttpClient.endChunks(socket);

            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(200, response.status());
            assertEquals("read 12", response.readBody());
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldReadABodyLargerThanTheConnectionsQueueBound() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1",
                "Host: localhost", "Content-Length: " + LARGE_BODY_BYTES);
            RawHttpClient.sendBody(socket, "x".repeat(LARGE_BODY_BYTES));

            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(200, response.status());
            assertEquals("read " + LARGE_BODY_BYTES, response.readBody(),
                "every byte must reach the handler, however far past the queue bound the body runs");
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldInviteABodyTheClientAsksAboutAndThenAnswerIt() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1",
                "Host: localhost", "Content-Length: 5", "Expect: 100-continue");

            RawHttpResponse interim = RawHttpResponse.read(socket.getInputStream());
            assertEquals(100, interim.status(), "a client that waits for an invitation must get one");

            RawHttpClient.sendBody(socket, "hello");
            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(200, response.status());
            assertEquals("read 5", response.readBody());
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldRefuseADeclaredBodyTooLargeToAcceptBeforeItIsSent() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1",
                "Host: localhost", "Content-Length: " + UNACCEPTABLE_CONTENT_LENGTH, "Expect: 100-continue");

            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(413, response.status(),
                "a body known from its length to be too large must be refused before the client sends it");
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldRefuseAnExpectationItCannotMeet() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1",
                "Host: localhost", "Content-Length: 5", "Expect: the-impossible");

            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(417, response.status());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldRefuseABodyThatOutgrowsTheLimitAsItArrives() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1",
                "Host: localhost", "Transfer-Encoding: chunked");
            Thread sender = Thread.ofVirtual().start(() -> sendUntilRefused(socket));

            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());

            assertEquals(413, response.status(),
                "a body with no declared length is bounded only by what has arrived");
            sender.join();
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shouldServeTheNextRequestOnAConnectionWhoseBodyTheHandlerIgnored() throws Exception {
        try (Socket socket = connect()) {
            RawHttpClient.send(socket, "POST /upload/ignored HTTP/1.1",
                "Host: localhost", "Content-Length: 11");
            RawHttpClient.sendBody(socket, "unread body");
            RawHttpResponse first = RawHttpResponse.read(socket.getInputStream());
            assertEquals(200, first.status());
            first.readBody();

            RawHttpClient.send(socket, "POST /upload/count HTTP/1.1", "Host: localhost", "Content-Length: 2");
            RawHttpClient.sendBody(socket, "ok");

            RawHttpResponse second = RawHttpResponse.read(socket.getInputStream());
            assertEquals(200, second.status(),
                "a body the handler never read must be drained, or it reads as the next request");
            assertEquals("read 2", second.readBody());
        }
    }

    /**
     * Writes until the server stops listening, which it does by closing behind the refusal — so a
     * broken pipe here is the outcome under test rather than a failure of it.
     */
    private static void sendUntilRefused(Socket socket) {
        try {
            String block = "y".repeat(16 * 1024);
            for (int sent = 0; sent < 4 * 1024 * 1024; sent += block.length()) {
                RawHttpClient.sendChunk(socket, block);
            }
        } catch (IOException refused) {
            assertTrue(true);
        }
    }

    private Socket connect() throws IOException {
        return RawHttpClient.connect(port, Duration.ofSeconds(15));
    }
}
