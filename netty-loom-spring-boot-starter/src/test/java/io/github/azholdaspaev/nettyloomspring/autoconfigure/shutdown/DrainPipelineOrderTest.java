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
 * The drain handler's correctness rests entirely on where it sits, and that position is decided by the
 * auto-configuration rather than by the pipelines the core tests hand-build: moving "drain" below
 * "bodyLimit" would break in-flight uploads on shutdown (issue #67) without failing a hand-built one.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DrainPipelineOrderTest {

    @Test
    void shouldPlaceTheDrainHandlerBelowKeepAliveAndAboveTheBodyLimit() {
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
            assertTrue(names.indexOf("drain") < names.indexOf("bodyLimit"),
                "drain must sit above bodyLimit so that handler's 100 Continue passes through it and is "
                    + "exempted, rather than ending the exchange that invitation opens; got " + names);

            channel.finishAndReleaseAll();
        }
    }
}
