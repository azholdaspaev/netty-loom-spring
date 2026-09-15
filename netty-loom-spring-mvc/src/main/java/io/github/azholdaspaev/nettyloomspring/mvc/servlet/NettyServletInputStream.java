package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * The request body as a servlet reads it. Blocking, because the thread it blocks is a virtual one
 * and the connection reads on only as this drains (issue #51).
 */
final class NettyServletInputStream extends ServletInputStream {

    private final InputStream body;
    private final long declaredLength;

    private long consumed;
    private boolean sawEof;

    NettyServletInputStream(InputStream body, long declaredLength) {
        this.body = body;
        this.declaredLength = declaredLength;
    }

    @Override
    public boolean isFinished() {
        return sawEof || (declaredLength >= 0 && consumed >= declaredLength);
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException("Async read not supported");
    }

    @Override
    public int read() throws IOException {
        int value = body.read();
        count(value < 0 ? -1 : 1);
        return value;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        return count(body.read(destination, offset, length));
    }

    @Override
    public int available() throws IOException {
        return body.available();
    }

    private int count(int read) {
        if (read < 0) {
            sawEof = true;
        } else {
            consumed += read;
        }
        return read;
    }
}
