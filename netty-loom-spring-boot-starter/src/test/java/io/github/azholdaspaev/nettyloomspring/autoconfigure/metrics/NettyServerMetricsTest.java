package io.github.azholdaspaev.nettyloomspring.autoconfigure.metrics;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyServerMetricsTest {

    private final HttpConnectionRegistry connectionRegistry =
        new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void shouldReportOpenConnections() {
        new NettyServerMetrics(connectionRegistry).bindTo(meterRegistry);

        connectionRegistry.register(new EmbeddedChannel());

        assertEquals(1, meterRegistry.get("netty.server.connections.active").gauge().value(),
            "the gauge must read the registry's open connections at scrape time");
    }

    @Test
    void shouldReportRunningDispatches() {
        new NettyServerMetrics(connectionRegistry).bindTo(meterRegistry);

        connectionRegistry.dispatchStarted();

        assertEquals(1, meterRegistry.get("netty.server.dispatches.active").gauge().value(),
            "the gauge must read the registry's active dispatches at scrape time");
    }
}
