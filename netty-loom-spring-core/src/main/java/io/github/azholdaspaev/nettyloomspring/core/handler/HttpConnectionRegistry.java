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

    private volatile boolean aborted;

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
     * Called on the event loop as the head of a request comes off the wire; returns whether the
     * exchange is admitted. An idle connection is closed while draining, as {@link #beginDrain()}
     * would have done had its close run first. Counting before reading {@code draining} is what makes
     * the drained verdict sound: either it sees this count, or this sees the drain and refuses.
     */
    public boolean exchangeStarted(Channel connection) {
        AtomicInteger inFlight = counter(connection);
        if (inFlight.incrementAndGet() == 1 && draining) {
            inFlight.decrementAndGet();
            connection.close();
            return false;
        }
        return true;
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
        /*
         * Only signal while draining: outside a shutdown nobody is waiting, and at low load every
         * request returns the count to zero, so this would take the lock on each one.
         */
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
     * whether anything is left in flight. The close future is what this waits on, not what it
     * reports: {@link #beginDrain()} closes an idle connection on that connection's own event loop,
     * so with no grace left that hop has not run, and a socket still in the group is not a request
     * (issue #206). Both counts are read, because neither brackets a request on its own — a
     * connection carries what no dispatch has reached yet, and a dispatch outlives the connection
     * whose client has hung up.
     */
    public boolean awaitDrained(long timeoutMillis) throws InterruptedException {
        beginDrain();
        long startNanos = System.nanoTime();
        connections.newCloseFuture().await(timeoutMillis, TimeUnit.MILLISECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        /*
         * aborted is read last: abortDrain sets it before closing the group, so a group the abort
         * emptied of cut-off exchanges is seen here rather than read as idle.
         */
        return awaitDispatchesFinished(Math.max(0L, timeoutMillis - elapsedMillis))
            && !hasExchangeInFlight()
            && !aborted;
    }

    boolean awaitDispatchesFinished(long timeoutMillis) throws InterruptedException {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        dispatchLock.lock();
        try {
            while (dispatchesInFlight.get() > 0) {
                if (aborted || remainingNanos <= 0) {
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

    /**
     * Cuts a running {@link #awaitDrained(long)} short. A flag rather than an interrupt: the thread
     * inside the wait belongs to the caller. Both flags are set before the close and the signal, so
     * a waiter about to park sees the abort and a connection registering after the close is closed
     * by {@link #register(Channel)} instead of holding the owner's drain open.
     */
    public void abortDrain() {
        draining = true;
        aborted = true;
        connections.close();
        dispatchLock.lock();
        try {
            dispatchesIdle.signalAll();
        } finally {
            dispatchLock.unlock();
        }
    }

    public ChannelGroupFuture closeAll() {
        return connections.close();
    }

    /**
     * Clears the drain and abort flags so a restarted server serves keep-alive connections again and
     * waits for its own dispatches. A dispatch abandoned by a shutdown that ran out of grace is still
     * running, so the count is not cleared — the restarted server starts non-zero and settles when
     * that thread's {@code finally} runs.
     */
    public void reset() {
        draining = false;
        aborted = false;
    }

    /**
     * Read off the event loop, and not through {@link #counter(Channel)}: that installs the
     * attribute, so the shutdown thread would touch every connection that never served a request.
     */
    private boolean hasExchangeInFlight() {
        return connections.stream()
            .map(connection -> connection.attr(IN_FLIGHT).get())
            .anyMatch(inFlight -> inFlight != null && inFlight.get() > 0);
    }

    private static void closeIfIdle(Channel connection) {
        /*
         * Decided on the connection's own event loop: exchangeStarted runs there too, so a request
         * already read off the wire has necessarily been counted before this check observes it.
         */
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
