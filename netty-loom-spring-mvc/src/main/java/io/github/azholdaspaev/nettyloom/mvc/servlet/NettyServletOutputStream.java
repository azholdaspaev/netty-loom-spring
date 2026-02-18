package io.github.azholdaspaev.nettyloom.mvc.servlet;

import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;

public class NettyServletOutputStream extends jakarta.servlet.ServletOutputStream {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    public void write(int b) {
        buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        buffer.write(b, off, len);
    }

    public byte[] toByteArray() {
        return buffer.toByteArray();
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setWriteListener(WriteListener listener) {}
}
