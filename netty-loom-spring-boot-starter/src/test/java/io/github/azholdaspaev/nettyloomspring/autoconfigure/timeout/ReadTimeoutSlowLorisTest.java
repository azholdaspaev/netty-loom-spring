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
 * Keeps its own 500ms rather than following {@link SlowHandlerNotTimedOutTest} up to 1s. Not because the
 * dribble needs it — a longer timeout would if anything make that relation more comfortable — but because
 * both tests here close <em>at</em> the timeout, so sharing 1s would add about a second of sleeping to
 * save a context boot measured at 60-100ms on this branch. The second context is the cheaper half.
 */
@SpringBootTest(
    classes = TimeoutTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.netty.read-timeout=" + ReadTimeoutSlowLorisTest.READ_TIMEOUT_MILLIS + "ms"
)
class ReadTimeoutSlowLorisTest {

    /** The server's own deadline. Shared with the annotation above so the two cannot drift apart. */
    static final int READ_TIMEOUT_MILLIS = 500;

    /** A request the client never finishes, sent a byte at a time. Its length sets how long that lasts. */
    private static final String DRIBBLE = "GET /a/path/this/client/never/finishes/sending";

    /** Must stay well inside {@link #READ_TIMEOUT_MILLIS} — see the assertions in the dribble test. */
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
            // Tight enough to kill a mis-wiring to 1000ms -- the value the sibling timeout test configures,
            // so the likeliest constant to end up here by copy-paste, and one a looser bound waves through
            // as a silent 2x. The bound has to clear the real close time, not some fraction of soTimeout:
            // measured 501-526ms, so this leaves ~475ms of headroom.
            assertTrue(elapsedMillis < READ_TIMEOUT_MILLIS * 2L,
                "closed after " + elapsedMillis + "ms, far past the configured " + READ_TIMEOUT_MILLIS + "ms");
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
        int soTimeout = (DRIBBLE.length() * DRIBBLE_INTERVAL_MILLIS) * 2 / 3;

        // This is the discrimination, and the only relation that carries it: a byte-level clock survives
        // a dribble only while each byte lands inside the timeout. Raise the interval past it -- a
        // plausible edit, to cut CPU -- and such a clock closes the connection too, greenlighting the
        // exact bug this guards while every assertion below still passes.
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
     * The dribbling client is closed on while bytes it sent are still sitting unread in the server's
     * receive buffer, and TCP answers a close with pending unread data by sending RST rather than FIN. So
     * the client sees either a clean EOF or a reset depending on how the last dribbled byte raced the
     * close — observed as a "Connection reset" failure once under full-build load having passed 5/5 in
     * isolation. Both outcomes are the server closing the connection, which is the whole assertion; only
     * the kernel's choice between them is racy, and it is not something the server can control.
     *
     * <p>Deliberately catches {@link SocketException} and not {@link IOException}: a {@code soTimeout}
     * expiry arrives as {@link java.net.SocketTimeoutException}, which extends
     * {@link java.io.InterruptedIOException} rather than {@code SocketException}, so a server that never
     * closes at all still fails here instead of being swallowed.
     */
    private static void assertServerClosedTheConnection(Socket socket) throws IOException {
        try {
            assertEquals(-1, socket.getInputStream().read(),
                "a request that never completes must not hold the connection open");
        } catch (SocketException reset) {
            // A reset is the server having closed too; see above.
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
