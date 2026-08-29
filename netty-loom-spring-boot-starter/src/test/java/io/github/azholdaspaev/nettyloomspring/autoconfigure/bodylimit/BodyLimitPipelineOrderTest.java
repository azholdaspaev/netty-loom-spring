package io.github.azholdaspaev.nettyloomspring.autoconfigure.bodylimit;

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
 * What the body limit guards rests entirely on where it sits, and that position is decided by the
 * auto-configuration rather than by the pipelines the core tests hand-build: above "decoderFailure" it
 * would count bytes of a message the codec had already rejected, and below "dispatcher" a body past the
 * limit would reach the application before anything refused it (issue #51).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BodyLimitPipelineOrderTest {

    @Test
    void shouldPlaceTheBodyLimitBelowDecoderFailureAndAboveTheDispatcher() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
            .properties("server.port=0")
            .run()) {

            EmbeddedChannel channel = new EmbeddedChannel();
            context.getBean(NettyPipelineConfigurer.class).configure(channel.pipeline());
            List<String> names = channel.pipeline().names();

            assertTrue(names.contains("bodyLimit"), "the auto-configured pipeline must install the body limit");
            assertTrue(names.indexOf("decoderFailure") < names.indexOf("bodyLimit"),
                "bodyLimit must sit below decoderFailure so it counts only what decoded; got " + names);
            assertTrue(names.indexOf("bodyLimit") < names.indexOf("dispatcher"),
                "bodyLimit must sit above the dispatcher so a body past the limit never reaches the "
                    + "application; got " + names);

            channel.finishAndReleaseAll();
        }
    }
}
