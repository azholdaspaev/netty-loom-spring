package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.github.azholdaspaev.nettyloomspring.core.support.SpinWait;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpConnectionRegistryTest {

    @Test
    void shouldCloseConnectionsWithNothingInFlightWhenDrainBegins() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel connection = register(registry);

        registry.beginDrain();
        connection.runPendingTasks();

        assertTrue(registry.isDraining());
        assertFalse(connection.isOpen(), "an idle connection has nothing to drain");
    }

    @Test
    void shouldKeepConnectionOpenUntilItsInFlightExchangeFinishes() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel connection = register(registry);
        registry.admitExchange(connection);

        registry.beginDrain();
        connection.runPendingTasks();

        assertTrue(connection.isOpen(), "a connection serving a request must survive the drain");

        registry.exchangeFinished(connection);
        connection.runPendingTasks();

        assertFalse(connection.isOpen(), "the connection must close once its last exchange is done");
    }

    @Test
    void shouldOnlyCloseAfterLastPipelinedExchangeFinishes() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel connection = register(registry);
        registry.admitExchange(connection);
        registry.admitExchange(connection);
        registry.beginDrain();
        connection.runPendingTasks();

        registry.exchangeFinished(connection);
        connection.runPendingTasks();

        assertTrue(connection.isOpen(), "one of two pipelined requests is still unanswered");

        registry.exchangeFinished(connection);
        connection.runPendingTasks();

        assertFalse(connection.isOpen());
    }

    @Test
    void shouldNotCloseFinishedExchangesWhileStillServing() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel connection = register(registry);

        registry.admitExchange(connection);
        registry.exchangeFinished(connection);
        connection.runPendingTasks();

        assertFalse(registry.isDraining());
        assertTrue(connection.isOpen(), "a keep-alive connection stays open between requests");
        connection.finish();
    }

    @Test
    void shouldCloseConnectionThatArrivesAfterDrainHasBegun() {
        HttpConnectionRegistry registry = newRegistry();
        registry.beginDrain();

        EmbeddedChannel latecomer = new EmbeddedChannel();
        registry.register(latecomer);
        latecomer.runPendingTasks();

        assertFalse(latecomer.isOpen(), "a connection accepted after the drain pass must not linger");
    }

    @Test
    void shouldCloseConnectionThatArrivesAfterDrainIsAborted() {
        HttpConnectionRegistry registry = newRegistry();
        registry.abortDrain();

        EmbeddedChannel latecomer = new EmbeddedChannel();
        registry.register(latecomer);
        latecomer.runPendingTasks();

        assertFalse(latecomer.isOpen(),
            "a connection accepted after the abort closed the group must not hold the owner's drain open");
    }

    @Test
    void shouldClearDrainingOnResetSoRestartKeepsConnectionsAlive() {
        HttpConnectionRegistry registry = newRegistry();
        registry.beginDrain();

        registry.reset();

        assertFalse(registry.isDraining(),
            "a server restarted after a shutdown must serve keep-alive connections again");
    }

    @Test
    void shouldReportDispatchesFinishedWhenNoneAreRunning() throws Exception {
        assertTrue(newRegistry().awaitDispatchesFinished(0),
            "a drain with nothing dispatching must not spend any of the grace period");
    }

    @Test
    void shouldWaitOutTimeoutWhileDispatchIsStillRunning() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        registry.dispatchStarted();

        assertFalse(registry.awaitDispatchesFinished(50),
            "a running dispatch must hold the drain open until the deadline");

        registry.dispatchFinished();

        assertTrue(registry.awaitDispatchesFinished(0),
            "the drain must be satisfied as soon as the dispatch unwinds");
    }

    @Test
    void shouldWakeDrainAsSoonAsLastDispatchFinishes() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        registry.dispatchStarted();
        registry.beginDrain();

        Thread drain = Thread.currentThread();
        long startNanos = System.nanoTime();
        Thread.ofPlatform().start(() -> {
            SpinWait.untilParked(() -> drain, Duration.ofSeconds(10), "the drain never parked");
            registry.dispatchFinished();
        });

        registry.awaitDispatchesFinished(5_000);

        assertTrue(System.nanoTime() - startNanos < TimeUnit.SECONDS.toNanos(2),
            "the drain must be woken by the dispatch, not released by its own timeout");
    }

    @Test
    void shouldStopWaitingForDispatchesWhenDrainIsAborted() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        registry.dispatchStarted();
        registry.beginDrain();

        Thread drain = Thread.currentThread();
        long startNanos = System.nanoTime();
        Thread.ofPlatform().start(() -> {
            SpinWait.untilParked(() -> drain, Duration.ofSeconds(10), "the drain never parked");
            registry.abortDrain();
        });

        assertFalse(registry.awaitDispatchesFinished(5_000),
            "an aborted drain must report the dispatch as still running");
        assertTrue(System.nanoTime() - startNanos < TimeUnit.SECONDS.toNanos(2),
            "the drain must be woken by the abort, not released by its own timeout");
    }

    @Test
    void shouldWaitForDispatchesAgainOnceResetFollowsAbort() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        registry.dispatchStarted();
        registry.abortDrain();

        registry.reset();

        long startNanos = System.nanoTime();
        assertFalse(registry.awaitDispatchesFinished(100),
            "a running dispatch must hold the drain open until the deadline");
        assertTrue(System.nanoTime() - startNanos >= TimeUnit.MILLISECONDS.toNanos(100),
            "a restarted server must not inherit the previous shutdown's abort");
    }

    @Test
    void shouldReportDrainedWithNoGraceWhileIdleConnectionIsOpen() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        register(registry);

        /*
         * No runPendingTasks: the idle-close task has not run, which is the state issue #206
         * reported. A drain that read the close future here would call the connection a live request.
         */
        assertTrue(registry.awaitDrained(0),
            "a connection with nothing in flight leaves nothing to drain, however little grace is left");
    }

    @Test
    void shouldRefuseExchangeOnIdleConnectionAfterDrainedVerdict() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel connection = register(registry);
        assertTrue(registry.awaitDrained(0), "nothing was in flight when the verdict was taken");

        registry.admitExchange(connection);
        connection.runPendingTasks();

        assertFalse(connection.isOpen(),
            "a request admitted after a drained verdict would be cut off by closeAll behind an IDLE result");
    }

    @Test
    void shouldReportNotDrainedWhileExchangeIsInFlight() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel connection = register(registry);
        registry.admitExchange(connection);

        assertFalse(registry.awaitDrained(0),
            "a connection still owing a response must be reported, not passed off as drained");
    }

    @Test
    void shouldReportNotDrainedWhileDispatchIsInFlight() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        registry.dispatchStarted();

        assertFalse(registry.awaitDrained(0),
            "a running dispatch must be reported even once its connection is gone");
    }

    @Test
    void shouldReportNotDrainedOnceAbortCutsOffExchange() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel connection = register(registry);
        registry.admitExchange(connection);

        Thread drain = Thread.currentThread();
        Thread.ofPlatform().start(() -> {
            SpinWait.untilParked(() -> drain, Duration.ofSeconds(10), "the drain never parked");
            registry.abortDrain();
        });

        assertFalse(registry.awaitDrained(5_000),
            "an abort that closed a connection still owing a response must not be reported as drained");
    }

    @Test
    void shouldReportNotDrainedWhenQueuedExchangeDispatchesMidVerdict() throws Exception {
        AtomicReference<Runnable> onStream = new AtomicReference<>(() -> { });
        HttpConnectionRegistry registry = new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE) {
                @Override
                public Stream<Channel> stream() {
                    onStream.getAndSet(() -> { }).run();
                    return super.stream();
                }
            });
        EmbeddedChannel connection = register(registry);
        registry.admitExchange(connection);
        /*
         * The hook stands in for the event loop running between the verdict's two reads: the queued
         * exchange is dispatched, and its response written, while its dispatch is still inside the handler.
         */
        onStream.set(() -> {
            registry.dispatchStarted();
            registry.exchangeFinished(connection);
        });

        assertFalse(registry.awaitDrained(0),
            "a dispatch started after the dispatch count was read must not be reported as drained");
    }

    private static HttpConnectionRegistry newRegistry() {
        return new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
    }

    private static EmbeddedChannel register(HttpConnectionRegistry registry) {
        EmbeddedChannel connection = new EmbeddedChannel();
        registry.register(connection);
        return connection;
    }
}
