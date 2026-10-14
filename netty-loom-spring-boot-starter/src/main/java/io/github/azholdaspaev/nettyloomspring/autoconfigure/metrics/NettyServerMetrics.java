package io.github.azholdaspaev.nettyloomspring.autoconfigure.metrics;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.binder.MeterBinder;

public class NettyServerMetrics implements MeterBinder {

    private final HttpConnectionRegistry connectionRegistry;

    public NettyServerMetrics(HttpConnectionRegistry connectionRegistry) {
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("netty.server.connections.active", connectionRegistry,
                HttpConnectionRegistry::getOpenConnectionCount)
            .description("Open connections, idle keep-alive connections included")
            .baseUnit(BaseUnits.CONNECTIONS)
            .register(registry);
        // TASKS rather than THREADS, Tomcat's busy-thread unit, because the dispatch executor is a replaceable bean.
        Gauge.builder("netty.server.dispatches.active", connectionRegistry,
                HttpConnectionRegistry::getActiveDispatchCount)
            .description("Requests dispatched to the application and not yet returned from it")
            .baseUnit(BaseUnits.TASKS)
            .register(registry);
    }
}
