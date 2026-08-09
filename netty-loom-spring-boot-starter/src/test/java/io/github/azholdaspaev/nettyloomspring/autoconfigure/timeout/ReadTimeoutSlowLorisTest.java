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
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps its own 500ms rather than following {@link SlowHandlerNotTimedOutTest} up to 1s: the tests here
 * close <em>at</em> the timeout, so sharing 1s would trade sleeping time for a saved context boot.
 */
@SpringBootTest(
    classes = TimeoutTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.netty.read-timeout=" + ReadTimeoutSlowLorisTest.READ_TIMEOUT_MILLIS + "ms"
)
class ReadTimeoutSlowLorisTest {

    static final int READ_TIMEOUT_MILLIS = 500;

    private static final String DRIBBLE = "GET /a/path/this/client/never/finishes/sending";

    private static final int DRIBBLE_INTERVAL_MILLIS = 100;

    @LocalServerPort
    int port;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCloseConnectionWhenClientSendsNoBytes() throws Exception {
        try (Socket socket = connect(5_000)) {
            long start = System.nanoTime();

            int firstByte = socket.getInputStream().read();

            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertEquals(-1, firstByte, "server should close the connection (EOF) after readTimeout");
            // Brackets the configured value end to end. Without this nothing ties the property to the
            // behaviour: hardcoding the wiring to any timeout in (0, 5000ms] left the whole suite green.
            assertTrue(elapsedMillis >= READ_TIMEOUT_MILLIS * 4 / 5,
                "closed after " + elapsedMillis + "ms, before the configured " + READ_TIMEOUT_MILLIS + "ms");
            // Tight enough to kill a mis-wiring to 1000ms, the likeliest constant to arrive here by
            // copy-paste, which a looser bound would wave through as a silent 2x.
            assertTrue(elapsedMillis < READ_TIMEOUT_MILLIS * 2L,
                "closed after " + elapsedMillis + "ms, far past the configured " + READ_TIMEOUT_MILLIS + "ms");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCloseConnectionWhenClientDribblesARequestItNeverCompletes() throws Exception {
        int soTimeout = (DRIBBLE.length() * DRIBBLE_INTERVAL_MILLIS) * 2 / 3;

        // The classic slow loris: a byte-level idle clock is refreshed by every byte, so a dribble holds
        // the connection open for ever, while measuring whole requests closes it (issue #76). That
        // discrimination survives only while each byte lands inside the timeout -- raise the interval past
        // it, a plausible edit to cut CPU, and a byte-level clock would close the connection too.
        assertTrue(DRIBBLE_INTERVAL_MILLIS < READ_TIMEOUT_MILLIS,
            "the dribble must out-pace the server's deadline or a byte-level clock would expire too");
        // Otherwise a regression surfaces as SocketTimeoutException rather than the assertion. Shortening
        // DRIBBLE far enough drags soTimeout under the server's deadline and trips this.
        assertTrue(READ_TIMEOUT_MILLIS < soTimeout,
            "the server must close before the client's read deadline, or the failure is unreadable");

        AtomicBoolean dribbling = new AtomicBoolean(true);
        AtomicInteger flushed = new AtomicInteger();
        try (Socket socket = connect(soTimeout)) {
            Thread dribbler = Thread.ofVirtual().start(() -> dribble(socket, dribbling, flushed));
            try {
                assertServerClosedTheConnection(socket);

                // Without this, a dribbler slow to be scheduled sends nothing, and the test silently
                // degrades into a duplicate of shouldCloseConnectionWhenClientSendsNoBytes -- passing.
                assertTrue(flushed.get() >= 2,
                    "the client must actually have dribbled; got " + flushed.get() + " bytes");
            } finally {
                dribbling.set(false);
                dribbler.join();
            }
        }
    }

    /**
     * Catches {@link SocketException} and not {@link IOException}: a {@code soTimeout} expiry arrives as
     * {@code SocketTimeoutException extends InterruptedIOException}, so a server that never closes fails.
     */
    private static void assertServerClosedTheConnection(Socket socket) throws IOException {
        try {
            assertEquals(-1, socket.getInputStream().read(),
                "a request that never completes must not hold the connection open");
        } catch (SocketException reset) {
            // TCP answers a close that still has unread data buffered with RST rather than FIN, so the
            // client sees a reset or a clean EOF depending on how the last dribbled byte raced the close.
            // Both are the server having closed, which is the whole assertion.
        }
    }

    private Socket connect(int soTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        socket.setSoTimeout(soTimeoutMillis);
        return socket;
    }

    private static void dribble(Socket socket, AtomicBoolean dribbling, AtomicInteger flushed) {
        try {
            OutputStream out = socket.getOutputStream();
            for (byte character : DRIBBLE.getBytes(StandardCharsets.US_ASCII)) {
                if (!dribbling.get()) {
                    return;
                }
                out.write(character);
                out.flush();
                flushed.incrementAndGet();
                Thread.sleep(DRIBBLE_INTERVAL_MILLIS);
            }
        } catch (IOException | InterruptedException expected) {
            // The server closing the connection mid-dribble is the outcome under test.
        }
    }
}
