package io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
    classes = SmokeNettyLoomApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.netty.read-timeout=500ms"
)
class ReadTimeoutSlowLorisTest {

    @LocalServerPort
    int port;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCloseConnectionWhenClientSendsNoBytes() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.setSoTimeout(5_000);

            int firstByte = socket.getInputStream().read();

            assertEquals(-1, firstByte, "server should close the connection (EOF) after readTimeout");
        }
    }
}
