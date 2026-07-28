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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps its own 500ms rather than following {@link SlowHandlerNotTimedOutTest} up to 1s: the dribble test
 * needs the timeout to sit well above {@link #DRIBBLE_INTERVAL_MILLIS} to stay distinguishable from the
 * no-bytes case, and raising it would lengthen the dribble in proportion. The differing property means a
 * second context, which is the price of keeping both gates sharp.
 */
@SpringBootTest(
    classes = TimeoutTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.netty.read-timeout=500ms"
)
class ReadTimeoutSlowLorisTest {

    /** A request the client never finishes, sent a byte at a time. Its length sets how long that lasts. */
    private static final String DRIBBLE = "GET /a/path/this/client/never/finishes/sending";

    /** Comfortably inside the 500ms timeout, so only a per-byte clock would survive the dribble. */
    private static final int DRIBBLE_INTERVAL_MILLIS = 100;

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
        // The teeth of this test are in the arithmetic: the read deadline has to expire while the client
        // is still dribbling. Let the dribble finish first and the client falls silent, at which point
        // even a byte-level clock closes the connection and this passes against the very bug it guards.
        int soTimeout = (DRIBBLE.length() * DRIBBLE_INTERVAL_MILLIS) * 2 / 3;
        assertTrue(soTimeout < DRIBBLE.length() * DRIBBLE_INTERVAL_MILLIS,
            "the read must time out mid-dribble or the test stops discriminating");

        AtomicBoolean dribbling = new AtomicBoolean(true);
        try (Socket socket = connect(soTimeout)) {
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

    private static void dribble(Socket socket, AtomicBoolean dribbling) {
        try {
            OutputStream out = socket.getOutputStream();
            for (byte character : DRIBBLE.getBytes(StandardCharsets.US_ASCII)) {
                if (!dribbling.get()) {
                    return;
                }
                out.write(character);
                out.flush();
                Thread.sleep(DRIBBLE_INTERVAL_MILLIS);
            }
        } catch (IOException | InterruptedException expected) {
            // The server closing the connection mid-dribble is the outcome under test.
        }
    }
}
