package io.github.azholdaspaev.nettyloomspring.autoconfigure.shutdown;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineConfigurer;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drain handler's correctness rests entirely on where it sits, and that position is decided by
 * the auto-configuration — not by the pipelines the core tests hand-build. Without this, moving
 * "drain" below "aggregator" would break in-flight uploads on shutdown (issue #67) while every other
 * test stayed green, because they each validate their own private copy of the order.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DrainPipelineOrderTest {

    @Test
    void shouldPlaceTheDrainHandlerBelowKeepAliveAndAboveTheAggregator() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
            .properties("server.port=0")
            .run()) {

            EmbeddedChannel channel = new EmbeddedChannel();
            context.getBean(NettyPipelineConfigurer.class).configure(channel.pipeline());
            List<String> names = channel.pipeline().names();

            assertTrue(names.contains("drain"), "the auto-configured pipeline must install the drain handler");
            assertTrue(names.indexOf("httpKeepAlive") < names.indexOf("drain"),
                "drain must be outbound of httpKeepAlive so it can stamp Connection: close before "
                    + "that handler decides whether to close; got " + names);
            assertTrue(names.indexOf("drain") < names.indexOf("aggregator"),
                "drain must sit above the aggregator so it sees the request head before the body has "
                    + "finished arriving; got " + names);

            channel.finishAndReleaseAll();
        }
    }
}
