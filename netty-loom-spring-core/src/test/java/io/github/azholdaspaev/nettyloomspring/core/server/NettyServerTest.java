package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.pipeline.DefaultNettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineConfigurer;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyServerTest {

    private NettyServer nettyServer;

    @BeforeEach
    void setup() {
        nettyServer = newServer(null);
    }

    private static NettyServer newServer(InetAddress address) {
        NettyServerConfiguration configuration = new NettyServerConfiguration(0, address, 0, 0, false);
        NettyPipelineConfigurer pipelineConfigurer = new DefaultNettyPipelineConfigurer(List.of());
        ChannelGroup channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        NettyServerChannelInitializer channelInitializer = new NettyServerChannelInitializer(pipelineConfigurer, channelGroup);
        NettyIoHandlerFactory nettyIoHandlerFactory = new NettyIoHandlerFactory("auto");
        return new NettyServer(configuration, channelInitializer, nettyIoHandlerFactory, channelGroup);
    }

    @AfterEach
    void tearDown() {
        if (nettyServer != null && nettyServer.isRunning()) {
            nettyServer.shutdown(Duration.ZERO);
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

        nettyServer.shutdown(Duration.ZERO);

        assertFalse(nettyServer.isRunning());
    }

    @Test
    void shouldNotThrowWhenStoppedTwice() {
        nettyServer.start();
        nettyServer.shutdown(Duration.ZERO);

        assertDoesNotThrow(() -> nettyServer.shutdown(Duration.ZERO));
    }

    @Test
    void shouldReturnPort() {
        nettyServer.start();

        assertTrue(nettyServer.getPort() > 0);
    }

    @Test
    void shouldBindToConfiguredAddress() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        nettyServer = newServer(loopback);
        nettyServer.start();

        assertEquals(loopback, nettyServer.getBoundAddress().getAddress());
        assertTrue(nettyServer.getPort() > 0);
    }

    @Test
    void shouldBindToWildcardWhenAddressNull() {
        nettyServer.start();

        assertTrue(nettyServer.getBoundAddress().getAddress().isAnyLocalAddress());
        assertTrue(nettyServer.getPort() > 0);
    }

    @Test
    void shouldReturnIdleWhenNoActiveConnections() {
        nettyServer.start();

        NettyShutdownResult result = nettyServer.shutdown(Duration.ofSeconds(1));

        assertEquals(NettyShutdownResult.IDLE, result);
        assertFalse(nettyServer.isRunning());
    }

    @Test
    void shouldRefuseNewConnectionsAfterCloseListener() {
        nettyServer.start();
        int port = nettyServer.getPort();

        nettyServer.stopAcceptingConnections();

        assertTrue(nettyServer.isRunning(), "server is still running during drain window");
        assertThrows(ConnectException.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            }
        });
    }

    @Test
    void shouldBeIdempotentOnGracefulShutdown() {
        nettyServer.start();

        assertEquals(NettyShutdownResult.IDLE, nettyServer.shutdown(Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> {
            assertEquals(NettyShutdownResult.IDLE, nettyServer.shutdown(Duration.ofSeconds(1)));
        });
    }
}
