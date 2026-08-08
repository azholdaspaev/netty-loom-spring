package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks what graceful shutdown must wait for: open connections, how many HTTP exchanges each is
 * still serving, and how many dispatches are running off the event loop. Shutdown has to wait for
 * in-flight requests, not open sockets — HTTP/1.1 makes persistence the default, so waiting on
 * socket liveness burned the whole grace period on every shutdown (issue #67). {@link #beginDrain()}
 * closes what is idle and flips {@link #isDraining()}, which makes {@link HttpDrainHandler} stamp
 * {@code Connection: close} on the response still owed. Dispatches are counted separately and
 * globally, because a client hanging up takes the per-connection count with it while a virtual
 * thread is still inside the handler (issue #108).
 */
public class HttpConnectionRegistry {

    private static final AttributeKey<AtomicInteger> IN_FLIGHT =
        AttributeKey.valueOf(HttpConnectionRegistry.class, "inFlight");

    private final ChannelGroup connections;

    private final AtomicInteger dispatchesInFlight = new AtomicInteger();

    private final ReentrantLock dispatchLock = new ReentrantLock();

    private final Condition dispatchesIdle = dispatchLock.newCondition();

    private volatile boolean draining;

    public HttpConnectionRegistry(ChannelGroup connections) {
        this.connections = connections;
    }

    /**
     * Adds an accepted connection, before the pipeline is configured, so a channel is always tracked
     * before it can carry a request. The accept path defers {@code initChannel} to the worker loop,
     * so a connection can land after {@link #beginDrain()} has already walked the group; closing it
     * straight away is always right, since nothing can be in flight on it yet.
     */
    public void register(Channel connection) {
        connections.add(connection);
        if (draining) {
            connection.close();
        }
    }

    public boolean isDraining() {
        return draining;
    }

    public int inFlight(Channel connection) {
        return counter(connection).get();
    }

    /**
     * Called on the event loop as the head of a request comes off the wire.
     */
    public void exchangeStarted(Channel connection) {
        counter(connection).incrementAndGet();
    }

    /**
     * Called on the event loop once a response has been written. The clamp is a safety net for an
     * unmatched response, which would otherwise make a busy connection look idle to
     * {@link #beginDrain()} and get it closed mid-request; reading before decrementing is sound only
     * because both run on this connection's event loop.
     */
    public void exchangeFinished(Channel connection) {
        AtomicInteger inFlight = counter(connection);
        if (inFlight.get() <= 0) {
            return;
        }
        if (inFlight.decrementAndGet() <= 0 && draining) {
            connection.close();
        }
    }

    /**
     * Counted before the dispatch is submitted, so a queued task is never invisible to a drain.
     */
    public void dispatchStarted() {
        dispatchesInFlight.incrementAndGet();
    }

    public void dispatchFinished() {
        // Only signal while draining: outside a shutdown nobody is waiting, and at low load every
        // request returns the count to zero, so this would take the lock on each one.
        if (dispatchesInFlight.decrementAndGet() <= 0 && draining) {
            dispatchLock.lock();
            try {
                dispatchesIdle.signalAll();
            } finally {
                dispatchLock.unlock();
            }
        }
    }

    /**
     * Starts draining and waits up to {@code timeoutMillis} for the server to fall quiet, reporting
     * whether it did. Connections first, then dispatches: once the connections are gone nothing can
     * start another dispatch, so from there the count only descends. Awaiting dispatches alone would
     * read a count that can rise again, which is why this is one method and not two calls.
     */
    public boolean awaitDrained(long timeoutMillis) throws InterruptedException {
        beginDrain();
        long startNanos = System.nanoTime();
        if (!connections.newCloseFuture().await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return false;
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return awaitDispatchesFinished(Math.max(0L, timeoutMillis - elapsedMillis));
    }

    boolean awaitDispatchesFinished(long timeoutMillis) throws InterruptedException {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        dispatchLock.lock();
        try {
            while (dispatchesInFlight.get() > 0) {
                if (remainingNanos <= 0) {
                    return false;
                }
                remainingNanos = dispatchesIdle.awaitNanos(remainingNanos);
            }
            return true;
        } finally {
            dispatchLock.unlock();
        }
    }

    /**
     * Starts draining: no connection may be reused from here on, and every connection that is idle
     * right now is closed. {@link #awaitDrained(long)} is how a shutdown waits for the result.
     */
    public void beginDrain() {
        draining = true;
        for (Channel connection : connections) {
            closeIfIdle(connection);
        }
    }

    public ChannelGroupFuture closeAll() {
        return connections.close();
    }

    /**
     * Clears the drain flag so a restarted server serves keep-alive connections again. A dispatch
     * abandoned by a shutdown that ran out of grace is still running, so the count is not cleared —
     * the restarted server starts non-zero and settles when that thread's {@code finally} runs.
     */
    public void reset() {
        draining = false;
    }

    private static void closeIfIdle(Channel connection) {
        // Decided on the connection's own event loop: exchangeStarted runs there too, so a request
        // already read off the wire has necessarily been counted before this check observes it.
        connection.eventLoop().execute(() -> {
            if (counter(connection).get() <= 0) {
                connection.close();
            }
        });
    }

    private static AtomicInteger counter(Channel connection) {
        Attribute<AtomicInteger> attribute = connection.attr(IN_FLIGHT);
        AtomicInteger counter = attribute.get();
        if (counter != null) {
            return counter;
        }
        AtomicInteger created = new AtomicInteger();
        AtomicInteger raced = attribute.setIfAbsent(created);
        return raced != null ? raced : created;
    }
}
