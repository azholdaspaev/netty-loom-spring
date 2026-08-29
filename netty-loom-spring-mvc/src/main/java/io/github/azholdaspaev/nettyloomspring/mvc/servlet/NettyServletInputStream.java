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

    private boolean finished;

    NettyServletInputStream(InputStream body) {
        this.body = body;
    }

    @Override
    public boolean isFinished() {
        return finished;
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
        return record(body.read());
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        return record(body.read(destination, offset, length));
    }

    @Override
    public int available() throws IOException {
        return body.available();
    }

    private int record(int read) {
        finished = read < 0;
        return read;
    }
}
