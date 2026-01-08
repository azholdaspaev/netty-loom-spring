package io.github.azholdaspaev.nettyloom.mvc.servlet;

import jakarta.servlet.WriteListener;
import jakarta.servlet.ServletOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Servlet output stream implementation that buffers response data
 * for later conversion to a Netty response.
 */
public class NettyServletOutputStream extends ServletOutputStream {

    private final ByteArrayOutputStream buffer;
    private WriteListener writeListener;
    private boolean closed;

    /**
     * Creates a new output stream with default initial buffer size.
     */
    public NettyServletOutputStream() {
        this(1024);
    }

    /**
     * Creates a new output stream with the specified initial buffer size.
     *
     * @param initialSize the initial buffer size in bytes
     */
    public NettyServletOutputStream(int initialSize) {
        this.buffer = new ByteArrayOutputStream(initialSize);
        this.closed = false;
    }

    @Override
    public void write(int b) throws IOException {
        checkClosed();
        buffer.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        checkClosed();
        buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        checkClosed();
        buffer.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        checkClosed();
        buffer.flush();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            buffer.close();
        }
    }

    @Override
    public boolean isReady() {
        // Always ready since we're writing to an in-memory buffer
        return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        this.writeListener = writeListener;
        // Since we're always ready to write, notify immediately
        if (writeListener != null) {
            try {
                writeListener.onWritePossible();
            } catch (IOException e) {
                writeListener.onError(e);
            }
        }
    }

    /**
     * Returns the buffered content as a byte array.
     *
     * @return the written bytes
     */
    public byte[] toByteArray() {
        return buffer.toByteArray();
    }

    /**
     * Returns the current size of the buffered content.
     *
     * @return the number of bytes written
     */
    public int size() {
        return buffer.size();
    }

    /**
     * Resets the buffer, discarding all written data.
     */
    public void reset() {
        buffer.reset();
    }

    /**
     * Checks if the stream has been closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed;
    }

    private void checkClosed() throws IOException {
        if (closed) {
            throw new IOException("Stream has been closed");
        }
    }
}
