package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The request body as the dispatch thread sees it: the event loop offers decoded parts, the virtual
 * thread reading blocks until they arrive. Bounded by {@link #HIGH_WATERMARK_BYTES}, which the
 * connection enforces by withholding reads rather than by blocking {@link #offer} — the producer is
 * the event loop and may never wait (issue #51).
 */
class HttpRequestBodyStream extends InputStream {

    /** Netty's own default write high-water mark, so both directions stall at the same depth. */
    static final int HIGH_WATERMARK_BYTES = 64 * 1024;

    static final int LOW_WATERMARK_BYTES = 32 * 1024;

    private final Runnable readRequester;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition partArrived = lock.newCondition();
    private final Deque<HttpContent> queued = new ArrayDeque<>();

    /** Offered but not yet consumed, {@link #reading}'s remainder included. */
    private final AtomicLong pendingBytes = new AtomicLong();

    private boolean ended;
    private Throwable failure;
    private boolean closed;

    /**
     * Read by the dispatch thread alone, so only it may release this — {@link #fail} runs on the event
     * loop while a copy out of this buffer may be in progress.
     */
    private ByteBuf reading;

    HttpRequestBodyStream(Runnable readRequester) {
        this.readRequester = readRequester;
    }

    // --- Producer: event loop ---

    /** Takes ownership of {@code part}, releasing it when nothing will read it. */
    void offer(HttpContent part) {
        boolean queueable;
        lock.lock();
        try {
            queueable = !closed && !ended && failure == null && part.content().isReadable();
            if (queueable) {
                queued.addLast(part);
                pendingBytes.addAndGet(part.content().readableBytes());
            }
            if (!closed && failure == null && part instanceof LastHttpContent) {
                ended = true;
            }
            partArrived.signalAll();
        } finally {
            lock.unlock();
        }
        if (!queueable) {
            part.release();
        }
    }

    /** Ends the body with {@code cause} rather than with an end a truncated upload would read as clean. */
    void fail(Throwable cause) {
        lock.lock();
        try {
            if (closed || failure != null) {
                return;
            }
            failure = cause;
            discardAndSignal();
        } finally {
            lock.unlock();
        }
    }

    boolean hasRoom() {
        return pendingBytes.get() < HIGH_WATERMARK_BYTES;
    }

    // --- Consumer: dispatch thread ---

    @Override
    public int read() throws IOException {
        ByteBuf chunk = nextReadable();
        if (chunk == null) {
            return -1;
        }
        int value = chunk.readUnsignedByte();
        consumed(chunk, 1);
        return value;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, destination.length);
        if (length == 0) {
            return 0;
        }
        ByteBuf chunk = nextReadable();
        if (chunk == null) {
            return -1;
        }
        int taken = Math.min(length, chunk.readableBytes());
        chunk.readBytes(destination, offset, taken);
        consumed(chunk, taken);
        return taken;
    }

    @Override
    public int available() {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, pendingBytes.get()));
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            discardAndSignal();
        } finally {
            lock.unlock();
        }
        if (reading != null) {
            reading.release();
            reading = null;
        }
    }

    private ByteBuf nextReadable() throws IOException {
        if (reading != null && reading.isReadable()) {
            return reading;
        }
        lock.lock();
        try {
            while (queued.isEmpty() && !ended && failure == null && !closed) {
                await();
            }
            if (failure != null) {
                throw asIoFailure(failure);
            }
            if (queued.isEmpty()) {
                return null;
            }
            reading = queued.pollFirst().content();
            return reading;
        } finally {
            lock.unlock();
        }
    }

    private void await() throws InterruptedIOException {
        try {
            partArrived.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted waiting for the request body");
        }
    }

    private void consumed(ByteBuf chunk, int bytes) {
        long remaining = pendingBytes.addAndGet(-bytes);
        if (!chunk.isReadable()) {
            reading = null;
            chunk.release();
        }
        if (remaining < LOW_WATERMARK_BYTES && remaining + bytes >= LOW_WATERMARK_BYTES) {
            readRequester.run();
        }
    }

    /** Signalled in a finally: a deallocator that throws must still not leave a reader waiting. */
    private void discardAndSignal() {
        try {
            discardQueued();
        } finally {
            partArrived.signalAll();
        }
    }

    private void discardQueued() {
        queued.forEach(HttpContent::release);
        queued.clear();
        pendingBytes.set(0);
    }

    private static IOException asIoFailure(Throwable cause) {
        return cause instanceof IOException failure ? failure : new IOException(cause);
    }
}
