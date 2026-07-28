package io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout.app.TimeoutTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shares {@link TimeoutTestApplication} and the shortened timeout with {@link SlowHandlerNotTimedOutTest}
 * so both classes run against one cached context rather than booting a server each.
 */
@SpringBootTest(
    classes = TimeoutTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.netty.read-timeout=500ms"
)
class ReadTimeoutSlowLorisTest {

    @LocalServerPort
    int port;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCloseConnectionWhenClientSendsNoBytes() throws Exception {
        try (Socket socket = connect(5_000)) {
            int firstByte = socket.getInputStream().read();

            assertEquals(-1, firstByte, "server should close the connection (EOF) after readTimeout");
        }
    }

    /**
     * The classic slow loris, which a byte-level idle clock never expires: every byte refreshes it, so
     * dribbling one faster than the timeout holds a connection open indefinitely. Measuring whole
     * requests instead closes it — the client has not delivered one within the interval (issue #76).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCloseConnectionWhenClientDribblesARequestItNeverCompletes() throws Exception {
        AtomicBoolean dribbling = new AtomicBoolean(true);
        // The read deadline is shorter than the dribble lasts, so a connection held open reads as a
        // failure rather than as the test simply outlasting the client.
        try (Socket socket = connect(3_000)) {
            Thread dribbler = Thread.ofVirtual().start(() -> dribble(socket, dribbling));
            try {
                int firstByte = socket.getInputStream().read();

                assertEquals(-1, firstByte, "a request that never completes must not hold the connection open");
            } finally {
                dribbling.set(false);
                dribbler.join();
            }
        }
    }

    private Socket connect(int soTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        socket.setSoTimeout(soTimeoutMillis);
        return socket;
    }

    /** One byte every 100ms — well inside the 500ms timeout, so only a per-byte clock would survive it. */
    private static void dribble(Socket socket, AtomicBoolean dribbling) {
        try {
            OutputStream out = socket.getOutputStream();
            for (byte character : "GET /a/path/this/client/never/finishes/sending".getBytes(StandardCharsets.US_ASCII)) {
                if (!dribbling.get()) {
                    return;
                }
                out.write(character);
                out.flush();
                Thread.sleep(100);
            }
        } catch (IOException | InterruptedException expected) {
            // The server closing the connection mid-dribble is the outcome under test.
        }
    }
}
