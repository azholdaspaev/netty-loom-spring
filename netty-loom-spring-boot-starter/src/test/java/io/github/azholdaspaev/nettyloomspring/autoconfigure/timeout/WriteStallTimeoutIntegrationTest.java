package io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.streaming.app.StreamingController;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.streaming.app.StreamingTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the bound is configurable is only worth anything if the configured value reaches the wire, and
 * a property bound but never read is the failure this container has most of (ADR 0001, "Properties
 * that are silently ignored"). Mirrors {@code ReadTimeoutSlowLorisTest} on the write side.
 */
@SpringBootTest(
    classes = StreamingTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.netty.write-stall-timeout=200ms"
)
class WriteStallTimeoutIntegrationTest {

    private static final int CLIENT_WINDOW_BYTES = 4096;

    /** Two orders past the configured bound, so what follows tests the give-up and not the clock. */
    private static final long PAST_THE_BOUND_MILLIS = 2_000;

    @LocalServerPort
    int port;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldGiveUpOnAClientThatStopsReadingAfterTheConfiguredBound() throws Exception {
        try (Socket socket = narrowWindowedClient()) {
            RawHttpClient.send(socket, "GET /streaming/large HTTP/1.1", "Host: localhost");

            Thread.sleep(PAST_THE_BOUND_MILLIS);

            assertTrue(readToEnd(socket) < StreamingController.LARGE_BODY_BYTES,
                "a client that stopped reading must be given up on part-way through the body");
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldDeliverTheWholeBodyToAClientThatKeepsReading() throws Exception {
        try (Socket socket = narrowWindowedClient()) {
            RawHttpClient.send(socket, "GET /streaming/large HTTP/1.1", "Host: localhost");

            assertTrue(readToEnd(socket) > StreamingController.LARGE_BODY_BYTES,
                "the same narrow window must deliver the whole body, plus framing, when it is drained");
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldLeaveAnOrdinaryResponseUntouchedByTheBound() throws Exception {
        try (Socket socket = narrowWindowedClient()) {
            RawHttpClient.send(socket, "GET /streaming/sized HTTP/1.1", "Host: localhost");

            assertTrue(readToEnd(socket) > 0, "a response that never stalls must not be bounded at all");
        }
    }

    /**
     * A receive buffer this small must be set before connect: it sizes the window advertised in the
     * handshake, and the server can only become unwritable once that window has closed.
     */
    private Socket narrowWindowedClient() throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(CLIENT_WINDOW_BYTES);
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(30));
        return socket;
    }

    /** Counts bytes rather than parsing: a truncated response is not a well-formed one to read. */
    private static int readToEnd(Socket socket) throws IOException {
        int total = 0;
        byte[] buffer = new byte[8192];
        try (InputStream in = socket.getInputStream()) {
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                total += read;
            }
        } catch (IOException reset) {
            // A give-up closes with bytes still queued, which the peer may report as a reset rather
            // than an orderly end. Both are the same event to this assertion.
        }
        return total;
    }
}
