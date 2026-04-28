package io.github.azholdaspaev.nettyloomspring.autoconfigure.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyLoomPropertiesTest {

    @Test
    void shouldApplyDefaultReadTimeout() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(Duration.ofSeconds(30), properties.readTimeout());
    }

    @Test
    void shouldOverrideReadTimeoutFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.read-timeout", "5s"));

        assertEquals(Duration.ofSeconds(5), properties.readTimeout());
    }

    private static NettyLoomProperties bind(Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source))
            .bindOrCreate("server.netty", NettyLoomProperties.class);
    }
}
