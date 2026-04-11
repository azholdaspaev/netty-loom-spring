package io.azholdaspaev.nettyloom.core.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyServerTest {

    private NettyServer nettyServer;

    @BeforeEach
    void setup() {
        NettyServerConfiguration configuration = new NettyServerConfiguration(0);
        nettyServer = new NettyServer(configuration);
    }

    @AfterEach
    void tearDown() {
        if (nettyServer != null && nettyServer.isRunning()) {
            nettyServer.stop();
        }
    }

    @Test
    void shouldStartServer() {
        nettyServer.start();

        assertTrue(nettyServer.isRunning());
    }

    @Test
    void shouldStopServer() {
        nettyServer.start();

        nettyServer.stop();

        assertFalse(nettyServer.isRunning());
    }

    @Test
    void shouldReturnPort() {
        nettyServer.start();

        assertTrue(nettyServer.getPort() > 0);
    }
}
