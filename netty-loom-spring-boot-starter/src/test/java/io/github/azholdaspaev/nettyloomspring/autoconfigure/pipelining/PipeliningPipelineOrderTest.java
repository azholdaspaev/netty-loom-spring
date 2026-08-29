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
 * the auto-configuration rather than by the pipelines the core tests hand-build: moving "pipelining"
 * below "bodyLimit" or below "dispatcher" would silently stop it serializing exchanges (issues #63, #78)
 * without failing a hand-built one.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PipeliningPipelineOrderTest {

    @Test
    void shouldPlaceThePipeliningHandlerAboveTheBodyLimitAndTheDispatcher() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
            .properties("server.port=0")
            .run()) {

            EmbeddedChannel channel = new EmbeddedChannel();
            context.getBean(NettyPipelineConfigurer.class).configure(channel.pipeline());
            List<String> names = channel.pipeline().names();

            assertTrue(names.contains("pipelining"),
                "the auto-configured pipeline must install the pipelining handler");
            assertTrue(names.indexOf("pipelining") < names.indexOf("bodyLimit"),
                "pipelining must sit above bodyLimit so that handler's 100 Continue and 413 are sequenced "
                    + "rather than travelling towards the head unsequenced (issue #78); got " + names);
            assertTrue(names.indexOf("pipelining") < names.indexOf("dispatcher"),
                "pipelining must sit above the dispatcher so requests are gated before dispatch while "
                    + "responses still pass back through it; got " + names);

            channel.finishAndReleaseAll();
        }
    }
}
