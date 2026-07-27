package io.github.azholdaspaev.nettyloomspring.autoconfigure.pipelining;

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
 * The pipelining handler's correctness rests entirely on where it sits, and that position is decided by
 * the auto-configuration — not by the pipelines the core tests hand-build. Without this, moving
 * "pipelining" above "aggregator" or below "dispatcher" would silently stop it serializing exchanges
 * (issue #63) while every other test stayed green, because they each validate their own private copy of
 * the order.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PipeliningPipelineOrderTest {

    @Test
    void shouldPlaceThePipeliningHandlerBelowTheAggregatorAndAboveTheDispatcher() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
            .properties("server.port=0")
            .run()) {

            EmbeddedChannel channel = new EmbeddedChannel();
            context.getBean(NettyPipelineConfigurer.class).configure(channel.pipeline());
            List<String> names = channel.pipeline().names();

            assertTrue(names.contains("pipelining"),
                "the auto-configured pipeline must install the pipelining handler");
            assertTrue(names.indexOf("aggregator") < names.indexOf("pipelining"),
                "pipelining must sit below the aggregator so it gates whole requests, and so the "
                    + "aggregator's 100 Continue never reaches it; got " + names);
            assertTrue(names.indexOf("pipelining") < names.indexOf("dispatcher"),
                "pipelining must sit above the dispatcher so requests are gated before dispatch while "
                    + "responses still pass back through it; got " + names);

            channel.finishAndReleaseAll();
        }
    }
}
