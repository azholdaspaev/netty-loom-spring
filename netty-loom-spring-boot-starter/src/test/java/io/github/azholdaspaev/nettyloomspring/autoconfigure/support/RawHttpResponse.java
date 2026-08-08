package io.github.azholdaspaev.nettyloomspring.autoconfigure.support;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * An HTTP response read straight off a socket: status line, headers lower-cased for lookup, and a body
 * left where it is until asked for.
 *
 * <p>Both framings are understood, and the body is deliberately <em>not</em> drained on construction —
 * that is what lets a test read one chunk of a streamed response and assert on it before the next one
 * has been produced.
 */
public final class RawHttpResponse {

    /** What {@link #readChunk()} returns for the zero-length chunk that ends a chunked body. */
    public static final String TERMINATOR = "";

    private final int status;
    private final Map<String, String> headers;
    private final InputStream body;

    private RawHttpResponse(int status, Map<String, String> headers, InputStream body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    /** Reads the status line and headers, stopping at the first byte of the body. */
    public static RawHttpResponse read(InputStream in) throws IOException {
        String statusLine = readLine(in);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        return new RawHttpResponse(Integer.parseInt(statusLine.split(" ")[1]), headers, in);
    }

    public int status() {
        return status;
    }

    public String header(CharSequence name) {
        return headers.get(name.toString());
    }

    public boolean isChunked() {
        return HttpHeaderValues.CHUNKED.contentEqualsIgnoreCase(header(HttpHeaderNames.TRANSFER_ENCODING));
    }

    /** Reads the next chunk, returning {@link #TERMINATOR} for the zero-length chunk that ends a body. */
    public String readChunk() throws IOException {
        int size = Integer.parseInt(readLine(body).trim(), 16);
        if (size == 0) {
            readLine(body);
            return TERMINATOR;
        }
        byte[] data = body.readNBytes(size);
        readLine(body);
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Reads the whole body, however it is framed. Also the way to consume a body that is not being
     * asserted on, so a reused socket starts its next read on a status line rather than on leftovers.
     */
    public String readBody() throws IOException {
        if (!isChunked()) {
            return new String(body.readNBytes(contentLength()), StandardCharsets.UTF_8);
        }
        StringBuilder whole = new StringBuilder();
        for (String chunk = readChunk(); !chunk.equals(TERMINATOR); chunk = readChunk()) {
            whole.append(chunk);
        }
        return whole.toString();
    }

    /** Counts the body without decoding it, for one too large to want as a String. */
    public int countBody() throws IOException {
        if (!isChunked()) {
            return body.readNBytes(contentLength()).length;
        }
        int counted = 0;
        for (String chunk = readChunk(); !chunk.equals(TERMINATOR); chunk = readChunk()) {
            counted += chunk.length();
        }
        return counted;
    }

    private int contentLength() {
        String value = header(HttpHeaderNames.CONTENT_LENGTH);
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
