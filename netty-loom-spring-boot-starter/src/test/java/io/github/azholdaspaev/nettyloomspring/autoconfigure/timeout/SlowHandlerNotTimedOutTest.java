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

/**
 * Issue #76 regression gate, end to end. The read timeout is set to a third of the handler's own
 * duration; before the fix the connection was closed at 500ms and the client got no response at all —
 * not a 504, just EOF — because the timeout ran against the dispatch rather than against the client.
 *
 * <p>Raw socket rather than a client library: the distinction being asserted is a status line versus a
 * bare TCP close, which is exactly what an HTTP client hides behind an IOException.
 */
@SpringBootTest(
    classes = TimeoutTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.netty.read-timeout=500ms"
)
class SlowHandlerNotTimedOutTest {

    @LocalServerPort
    int port;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldAnswerAHandlerThatRunsLongerThanTheReadTimeout() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.setSoTimeout((int) (SlowController.DELAY_MILLIS * 10));

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
