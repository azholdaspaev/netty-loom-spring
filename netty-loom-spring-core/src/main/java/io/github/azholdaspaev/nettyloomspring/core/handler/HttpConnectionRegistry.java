package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks open connections and, per connection, how many HTTP exchanges are still in flight.
 *
 * <p>Graceful shutdown has to wait for in-flight <em>requests</em>, not for open <em>sockets</em>.
 * Those are only the same thing if the server closes the connection after every response, which
 * HTTP/1.1 does not: persistence is the protocol default, so a pooled connection sitting idle stays
 * open until the client decides otherwise. Waiting on socket liveness therefore burned the whole
 * grace period on every shutdown (issue #67).
 *
 * <p>{@link #beginDrain()} closes the connections that are idle at that moment and flips
 * {@link #isDraining()}, which makes {@link HttpDrainHandler} stamp {@code Connection: close} on
 * the response still owed; Netty's {@code HttpServerKeepAliveHandler} then closes those
 * connections once they have replied. The returned future consequently completes as soon as the
 * last in-flight exchange finishes, and a well-behaved client stops reusing the connection instead
 * of racing the close.
 */
public class HttpConnectionRegistry {

    private static final AttributeKey<AtomicInteger> IN_FLIGHT =
        AttributeKey.valueOf(HttpConnectionRegistry.class, "inFlight");

    private final ChannelGroup connections;

    private volatile boolean draining;

    public HttpConnectionRegistry(ChannelGroup connections) {
        this.connections = connections;
    }

    /**
     * Adds an accepted connection. Called before the pipeline is configured, so a channel is always
     * tracked before it can carry a request.
     */
    public void register(Channel connection) {
        connections.add(connection);
    }

    public boolean isDraining() {
        return draining;
    }

    /** Exchanges this connection has begun but not yet answered. */
    public int inFlight(Channel connection) {
        return counter(connection).get();
    }

    /** Called on the event loop as the head of a request comes off the wire. */
    public void exchangeStarted(Channel connection) {
        counter(connection).incrementAndGet();
    }

    /**
     * Called on the event loop once a response has been written.
     *
     * <p>Ignores a connection with nothing outstanding rather than going negative: the pipeline
     * answers some requests that never became an exchange here — a malformed request line is
     * rejected by the codec before any {@code HttpRequest} is emitted, yet its 400 still travels out
     * past {@link HttpDrainHandler}. Letting the count drift below zero would make the next real
     * request look like nothing was in flight, and a drain would close the connection under it.
     * Those connections are closed by {@link HttpExceptionHandler} anyway, so declining to count
     * them cannot strand the drain.
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
     * Starts draining: no connection may be reused from here on, and every connection that is idle
     * right now is closed. Returns a future completing once every connection open at this instant
     * has closed — which, since only busy ones are left, means once the last in-flight exchange has
     * been answered.
     */
    public ChannelGroupFuture beginDrain() {
        draining = true;
        for (Channel connection : connections) {
            closeIfIdle(connection);
        }
        return connections.newCloseFuture();
    }

    /** Force-closes every connection, abandoning whatever is still in flight. */
    public ChannelGroupFuture closeAll() {
        return connections.close();
    }

    /** Clears the drain flag so a restarted server serves keep-alive connections again. */
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
