package io.github.azholdaspaev.nettyloomspring.autoconfigure.keepalive;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Keep-alive lives on the wire: {@code Connection} headers and socket lifetime are exactly what
 * RestTestClient and java.net.http.HttpClient hide, so these tests speak HTTP over a raw socket.
 */
@SpringBootTest(
    classes = SmokeNettyLoomApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class HttpKeepAliveIntegrationTest {

    private static final String PATH = "/api/greeting";

    @LocalServerPort
    int port;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReuseConnectionForSequentialHttp11Requests() throws Exception {
        try (Socket socket = connect()) {
            Response first = exchange(socket, "GET " + PATH + " HTTP/1.1", "Host: localhost");
            Response second = exchange(socket, "GET " + PATH + " HTTP/1.1", "Host: localhost");

            assertEquals(200, first.status(), "first request on a fresh connection should succeed");
            assertNull(first.header("connection"),
                "HTTP/1.1 keep-alive is the default and needs no Connection header");
            assertEquals(200, second.status(), "the same connection must serve a second request");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCloseConnectionWhenClientRequestsClose() throws Exception {
        try (Socket socket = connect()) {
            Response response = exchange(socket, "GET " + PATH + " HTTP/1.1",
                "Host: localhost", "Connection: close");

            assertEquals(200, response.status());
            assertEquals("close", response.header("connection"),
                "a requested close must be echoed on the response");
            assertEquals(-1, socket.getInputStream().read(),
                "server must close the connection (EOF) after honouring Connection: close");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCloseConnectionForHttp10WithoutKeepAlive() throws Exception {
        try (Socket socket = connect()) {
            Response response = exchange(socket, "GET " + PATH + " HTTP/1.0");

            assertEquals(200, response.status());
            assertEquals("close", response.header("connection"),
                "HTTP/1.0 defaults to close, which must be spelled out on the response");
            assertEquals(-1, socket.getInputStream().read(),
                "server must close the connection (EOF) after an HTTP/1.0 request without keep-alive");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldReuseConnectionForHttp10WithKeepAlive() throws Exception {
        try (Socket socket = connect()) {
            Response first = exchange(socket, "GET " + PATH + " HTTP/1.0", "Connection: keep-alive");

            assertEquals(200, first.status());
            assertEquals("keep-alive", first.header("connection"),
                "an HTTP/1.0 client only reuses the connection when keep-alive is spelled out");

            Response second = exchange(socket, "GET " + PATH + " HTTP/1.0", "Connection: keep-alive");
            assertEquals(200, second.status(), "the same connection must serve a second HTTP/1.0 request");
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static Response exchange(Socket socket, String requestLine, String... headers) throws IOException {
        StringBuilder request = new StringBuilder(requestLine).append("\r\n");
        for (String header : headers) {
            request.append(header).append("\r\n");
        }
        request.append("\r\n");

        OutputStream out = socket.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        return Response.read(socket.getInputStream());
    }

    /** A parsed HTTP response: status code, header names lower-cased for lookup, body drained. */
    private record Response(int status, Map<String, String> headers) {

        static Response read(InputStream in) throws IOException {
            String statusLine = readLine(in);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            Response response = new Response(Integer.parseInt(statusLine.split(" ")[1]), headers);
            // Drain the body so a reused socket starts the next read on a status line, not leftover bytes.
            in.readNBytes(response.contentLength());
            return response;
        }

        String header(String name) {
            return headers.get(name);
        }

        private int contentLength() {
            String value = header("content-length");
            return value == null ? 0 : Integer.parseInt(value);
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder line = new StringBuilder();
            for (int c = in.read(); c != '\n'; c = in.read()) {
                if (c == -1) {
                    throw new IOException("connection closed mid-response, read so far: " + line);
                }
                if (c != '\r') {
                    line.append((char) c);
                }
            }
            return line.toString();
        }
    }
}
