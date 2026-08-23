package io.github.azholdaspaev.nettyloomspring.autoconfigure.properties;

import io.github.azholdaspaev.nettyloomspring.core.server.NettyTransportPreference;
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

    @Test
    void shouldApplyDefaultWriteStallTimeout() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(Duration.ofSeconds(60), properties.writeStallTimeout());
    }

    @Test
    void shouldOverrideWriteStallTimeoutFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.write-stall-timeout", "5s"));

        assertEquals(Duration.ofSeconds(5), properties.writeStallTimeout());
    }

    @Test
    void shouldDefaultTransportToAuto() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(NettyTransportPreference.AUTO, properties.transport());
    }

    @Test
    void shouldOverrideTransportFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.transport", "nio"));

        assertEquals(NettyTransportPreference.NIO, properties.transport());
    }

    @Test
    void shouldFallBackToAutoWhenTransportIsBlank() {
        NettyLoomProperties properties = bind(Map.of("server.netty.transport", ""));

        assertEquals(NettyTransportPreference.AUTO, properties.transport());
    }

    private static NettyLoomProperties bind(Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source))
            .bindOrCreate("server.netty", NettyLoomProperties.class);
    }
}
