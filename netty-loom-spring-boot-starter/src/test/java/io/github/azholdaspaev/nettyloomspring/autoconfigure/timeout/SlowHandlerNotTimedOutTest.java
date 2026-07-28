package io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout.app.SlowController;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout.app.TimeoutTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #76 regression gate, end to end. The handler outlasts the read timeout, and before the fix that
 * closed the connection with no response at all — not a 504, just EOF — because the timeout ran against
 * the dispatch rather than against the client.
 *
 * <p>The timeout must stay below {@link SlowController#DELAY_MILLIS} for the gate to have teeth, and far
 * enough below it to absorb the connect-to-request window: the clock arms when the connection is
 * accepted and only stops once the whole request has arrived, so a stalled runner between
 * {@code connect()} and {@code flush()} would close the connection and fail with the same null status
 * line the real regression produces. A flake here would read as a genuine bug, which is why the budget
 * is 1s rather than the tightest value that passes.
 *
 * <p>Raw socket rather than a client library: the distinction being asserted is a status line versus a
 * bare TCP close, which is exactly what an HTTP client hides behind an IOException.
 */
@SpringBootTest(
    classes = TimeoutTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.netty.read-timeout=" + SlowHandlerNotTimedOutTest.READ_TIMEOUT_MILLIS + "ms"
)
class SlowHandlerNotTimedOutTest {

    /** Shared with the annotation above, and asserted against the handler's delay below. */
    static final int READ_TIMEOUT_MILLIS = 1_000;

    @LocalServerPort
    int port;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldAnswerAHandlerThatRunsLongerThanTheReadTimeout() throws Exception {
        // The gate has teeth only while the handler outlasts the timeout. Raising the property past the
        // delay would leave this passing having asserted nothing at all.
        assertTrue(READ_TIMEOUT_MILLIS < SlowController.DELAY_MILLIS,
            "the handler must outlast the read timeout or this gate is vacuous");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            // Comfortably past the handler's own delay, and inside the method timeout above so the socket
            // deadline is the one that can actually fire.
            socket.setSoTimeout((int) (SlowController.DELAY_MILLIS * 3));

            OutputStream out = socket.getOutputStream();
            out.write(("GET " + SlowController.PATH + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

            assertEquals("HTTP/1.1 200 OK", in.readLine(),
                "time spent computing the answer must not count against the read timeout");
        }
    }
}
