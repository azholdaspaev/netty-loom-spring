package io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout;

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
 * What the read timeout measures rests entirely on where it sits, and that position is decided by the
 * auto-configuration rather than by the pipelines the core tests hand-build: at the head, where it used
 * to be, it counted dispatch time and closed connections mid-request (issue #76).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReadTimeoutPipelineOrderTest {

    @Test
    void shouldPlaceTheReadTimeoutAboveThePipeliningGateAndTheBodyLimit() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
            .properties("server.port=0")
            .run()) {

            EmbeddedChannel channel = new EmbeddedChannel();
            context.getBean(NettyPipelineConfigurer.class).configure(channel.pipeline());
            List<String> names = channel.pipeline().names();

            assertTrue(names.contains("readTimeout"),
                "the auto-configured pipeline must install the read timeout");
            assertTrue(names.indexOf("readTimeout") < names.indexOf("bodyLimit"),
                "readTimeout must sit above bodyLimit so that handler's 100 Continue passes through it and "
                    + "is exempted, rather than counting the exchange answered; got " + names);
            assertTrue(names.indexOf("readTimeout") < names.indexOf("pipelining"),
                "readTimeout must sit above the pipelining gate so its count stays a property of what the "
                    + "client has delivered rather than of what that gate has released; got " + names);

            channel.finishAndReleaseAll();
        }
    }
}
