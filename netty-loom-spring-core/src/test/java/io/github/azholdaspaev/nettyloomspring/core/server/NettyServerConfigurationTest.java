package io.github.azholdaspaev.nettyloomspring.core.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyServerConfigurationTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -2})
    void shouldRejectMaxConnectionsNeitherPositiveNorUnlimited(int maxConnections) {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
            () -> new NettyServerConfiguration(0, null, 0, 0, false, 128, maxConnections),
            "only -1 may stand for no limit, so a core caller passing " + maxConnections + " must not get one");
        assertTrue(rejected.getMessage().contains("maxConnections"),
            "the rejection must name the field; message: " + rejected.getMessage());
    }

    @Test
    void shouldAcceptUnlimitedMaxConnections() {
        assertDoesNotThrow(() -> new NettyServerConfiguration(
            0, null, 0, 0, false, 128, NettyServerConfiguration.UNLIMITED_CONNECTIONS));
    }
}
