package io.github.azholdaspaev.nettyloomspring.autoconfigure.accesslog;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineDefinition;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AccessLogPipelineOrderTest {

    @Test
    void shouldPlaceAccessLogBelowPipeliningAndAboveDecoderFailure() {
        try (ConfigurableApplicationContext context =
                 NettyLoomApplications.run("server.netty.access-log.enabled=true")) {
            List<String> names = pipelineNames(context);

            assertTrue(names.indexOf("pipelining") >= 0 && names.indexOf("pipelining") < names.indexOf("accessLog"),
                "below the gate the step sees one exchange at a time, and refused requests never reach it; got "
                    + names);
            assertTrue(names.indexOf("accessLog") < names.indexOf("decoderFailure"),
                "every response, decoder rejections included, must originate below the step; got " + names);
        }
    }

    @Test
    void shouldLeaveAccessLogOutByDefault() {
        try (ConfigurableApplicationContext context = NettyLoomApplications.run()) {
            List<String> names = pipelineNames(context);

            assertFalse(names.contains("accessLog"), "the access log is opt-in; got " + names);
        }
    }

    private static List<String> pipelineNames(ConfigurableApplicationContext context) {
        EmbeddedChannel channel = new EmbeddedChannel();
        context.getBean(NettyPipelineDefinition.class).applyTo(channel.pipeline());
        List<String> names = channel.pipeline().names();
        channel.finishAndReleaseAll();
        return names;
    }
}
