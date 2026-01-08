package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.netty.buffer.ByteBuf;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.IOException;

/**
 * Servlet input stream implementation that wraps a Netty ByteBuf.
 * Provides read access to the HTTP request body.
 */
public class NettyServletInputStream extends ServletInputStream {

    private final ByteBuf content;
    private boolean finished;
    private ReadListener readListener;

    /**
     * Creates a new input stream wrapping the given ByteBuf.
     *
     * @param content the ByteBuf containing the request body
     */
    public NettyServletInputStream(ByteBuf content) {
        this.content = content;
        this.finished = false;
    }

    @Override
    public int read() throws IOException {
        if (!content.isReadable()) {
            finished = true;
            return -1;
        }
        return content.readByte() & 0xFF;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("Buffer is null");
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }

        int available = content.readableBytes();
        if (available == 0) {
            finished = true;
            return -1;
        }

        int bytesToRead = Math.min(len, available);
        content.readBytes(b, off, bytesToRead);

        if (!content.isReadable()) {
            finished = true;
        }

        return bytesToRead;
    }

    @Override
    public int available() throws IOException {
        return content.readableBytes();
    }

    @Override
    public void close() throws IOException {
        finished = true;
    }

    @Override
    public boolean isFinished() {
        return finished || !content.isReadable();
    }

    @Override
    public boolean isReady() {
        // Always ready since we have the full content in memory
        return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        this.readListener = readListener;
        // Since we already have all data, notify immediately that data is available
        if (readListener != null && content.isReadable()) {
            try {
                readListener.onDataAvailable();
            } catch (IOException e) {
                readListener.onError(e);
            }
        }
    }

    /**
     * Returns the underlying ByteBuf.
     * Use with caution - modifying the buffer affects the stream.
     *
     * @return the underlying ByteBuf
     */
    public ByteBuf getContent() {
        return content;
    }
}
