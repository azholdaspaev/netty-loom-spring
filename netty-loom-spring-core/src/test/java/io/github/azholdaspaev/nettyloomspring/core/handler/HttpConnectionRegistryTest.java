package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.github.azholdaspaev.nettyloomspring.core.support.SpinWait;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
        registry.exchangeStarted(connection);

        registry.beginDrain();
        connection.runPendingTasks();

        assertTrue(connection.isOpen(), "a connection serving a request must survive the drain");

        registry.exchangeFinished(connection);
        connection.runPendingTasks();

        assertFalse(connection.isOpen(), "the connection must close once its last exchange is done");
    }

    @Test
    void shouldOnlyCloseAfterTheLastPipelinedExchangeFinishes() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel connection = register(registry);
        registry.exchangeStarted(connection);
        registry.exchangeStarted(connection);
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

        registry.exchangeStarted(connection);
        registry.exchangeFinished(connection);
        connection.runPendingTasks();

        assertFalse(registry.isDraining());
        assertTrue(connection.isOpen(), "a keep-alive connection stays open between requests");
        connection.finish();
    }

    @Test
    void shouldCloseAConnectionThatArrivesAfterTheDrainHasBegun() {
        HttpConnectionRegistry registry = newRegistry();
        registry.beginDrain();

        EmbeddedChannel latecomer = new EmbeddedChannel();
        registry.register(latecomer);
        latecomer.runPendingTasks();

        assertFalse(latecomer.isOpen(), "a connection accepted after the drain pass must not linger");
    }

    @Test
    void shouldClearDrainingOnResetSoARestartedServerKeepsConnectionsAlive() {
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
    void shouldWaitOutTheTimeoutWhileADispatchIsStillRunning() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        registry.dispatchStarted();

        assertFalse(registry.awaitDispatchesFinished(50),
            "a running dispatch must hold the drain open until the deadline");

        registry.dispatchFinished();

        assertTrue(registry.awaitDispatchesFinished(0),
            "the drain must be satisfied as soon as the dispatch unwinds");
    }

    @Test
    void shouldWakeTheDrainAsSoonAsTheLastDispatchFinishes() throws Exception {
        HttpConnectionRegistry registry = newRegistry();
        registry.dispatchStarted();
        registry.beginDrain();

        Thread drain = Thread.currentThread();
        long startNanos = System.nanoTime();
        Thread.ofPlatform().start(() -> {
            SpinWait.until(() -> drain.getState() == Thread.State.TIMED_WAITING,
                Duration.ofSeconds(10), "the drain never parked");
            registry.dispatchFinished();
        });

        registry.awaitDispatchesFinished(5_000);

        assertTrue(System.nanoTime() - startNanos < TimeUnit.SECONDS.toNanos(2),
            "the drain must be woken by the dispatch, not released by its own timeout");
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
