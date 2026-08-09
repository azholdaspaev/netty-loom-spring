package io.github.azholdaspaev.nettyloomspring.autoconfigure.decoder;

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
 * Whether a rejection is sequenced rests entirely on where this handler sits, and that position is decided
 * by the auto-configuration rather than by the pipelines the core tests hand-build: above the gate, a
 * rejection reaches the wire ahead of an earlier pipelined response and closes the connection out from
 * under it (issue #78).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DecoderFailurePipelineOrderTest {

    @Test
    void shouldPlaceTheDecoderFailureHandlerBelowThePipeliningGateAndAboveTheDispatcher() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
            .properties("server.port=0")
            .run()) {

            EmbeddedChannel channel = new EmbeddedChannel();
            context.getBean(NettyPipelineConfigurer.class).configure(channel.pipeline());
            List<String> names = channel.pipeline().names();

            assertTrue(names.contains("decoderFailure"),
                "the auto-configured pipeline must install the decoder-failure handler");
            assertTrue(names.indexOf("pipelining") < names.indexOf("decoderFailure"),
                "decoderFailure must sit below the pipelining gate so its rejection is sequenced behind an "
                    + "earlier pipelined response rather than overtaking it and closing the connection; got " + names);
            assertTrue(names.indexOf("decoderFailure") < names.indexOf("dispatcher"),
                "decoderFailure must sit above the dispatcher so a request the codec could not parse never "
                    + "reaches the application; got " + names);

            channel.finishAndReleaseAll();
        }
    }
}
