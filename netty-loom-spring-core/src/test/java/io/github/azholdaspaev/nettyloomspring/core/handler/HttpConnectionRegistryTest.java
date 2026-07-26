package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

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
    void shouldForceCloseEverythingOnCloseAll() {
        HttpConnectionRegistry registry = newRegistry();
        EmbeddedChannel connection = register(registry);
        registry.exchangeStarted(connection);

        registry.closeAll();
        connection.runPendingTasks();

        assertFalse(connection.isOpen(), "closeAll abandons whatever is still in flight");
    }

    @Test
    void shouldClearDrainingOnResetSoARestartedServerKeepsConnectionsAlive() {
        HttpConnectionRegistry registry = newRegistry();
        registry.beginDrain();

        registry.reset();

        assertFalse(registry.isDraining(),
            "a server restarted after a shutdown must serve keep-alive connections again");
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
