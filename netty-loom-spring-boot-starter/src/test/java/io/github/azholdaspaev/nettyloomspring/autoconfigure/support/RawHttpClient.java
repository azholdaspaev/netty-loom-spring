package io.github.azholdaspaev.nettyloomspring.autoconfigure.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The request half of speaking HTTP over a raw socket, paired with {@link RawHttpResponse}.
 *
 * <p>Some things are only observable on the wire — keep-alive's {@code Connection} headers and socket
 * lifetime, chunked framing, whether a response arrives in pieces — and both an HTTP client library
 * and {@code RestTestClient} hide exactly those.
 */
public final class RawHttpClient {

    private RawHttpClient() {
    }

    public static Socket connect(int port, Duration readTimeout) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        socket.setSoTimeout((int) readTimeout.toMillis());
        return socket;
    }

    /** Sends a request and leaves the response on the socket for the caller to read as it chooses. */
    public static void send(Socket socket, String requestLine, String... headers) throws IOException {
        StringBuilder request = new StringBuilder(requestLine).append("\r\n");
        for (String header : headers) {
            request.append(header).append("\r\n");
        }
        request.append("\r\n");

        OutputStream out = socket.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
