package io.github.azholdaspaev.nettyloomspring.core.support;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HttpWireClient implements Closeable {

    private static final int CONNECT_TIMEOUT_MILLIS = 1_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private final Socket socket;

    /** One per socket, not one per read: a fresh reader discards what the previous had buffered. */
    private final BufferedReader reader;

    private HttpWireClient(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
    }

    public static HttpWireClient connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS);
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        return new HttpWireClient(socket);
    }

    public void send(String bytes) throws IOException {
        socket.getOutputStream().write(bytes.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    public String readLine() throws IOException {
        return reader.readLine();
    }

    public List<String> readHeaderBlock() throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    /**
     * Header block, then exactly Content-Length bytes -- US-ASCII, so one char is one byte.
     */
    public String readResponseBody() throws IOException {
        int contentLength = contentLength(readHeaderBlock());
        char[] body = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = reader.read(body, read, contentLength - read);
            if (count < 0) {
                throw new EOFException("connection closed after " + read + " of " + contentLength + " body bytes");
            }
            read += count;
        }
        return new String(body);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private static int contentLength(List<String> headerBlock) {
        return headerBlock.stream()
            .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
            .map(line -> line.substring(line.indexOf(':') + 1).trim())
            .mapToInt(Integer::parseInt)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no Content-Length in " + headerBlock));
    }
}
