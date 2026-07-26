package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.DefaultNettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NamedChannelHandler;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineConfigurer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        return newServer(address, null);
    }

    /**
     * @param accepted counted down once the server has accepted a connection, so a test can act on a
     *                 connection the server definitely knows about rather than racing the accept.
     */
    private static NettyServer newServer(InetAddress address, CountDownLatch accepted) {
        NettyServerConfiguration configuration = new NettyServerConfiguration(0, address, 0, 0, false);
        NettyPipelineConfigurer pipelineConfigurer = new DefaultNettyPipelineConfigurer(
            accepted == null ? List.of() : List.of(new NamedChannelHandler("accepted", () -> new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    accepted.countDown();
                    ctx.fireChannelActive();
                }
            })));
        HttpConnectionRegistry connectionRegistry = new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
        NettyServerChannelInitializer channelInitializer = new NettyServerChannelInitializer(pipelineConfigurer, connectionRegistry);
        NettyIoHandlerFactory nettyIoHandlerFactory = new NettyIoHandlerFactory("auto");
        return new NettyServer(configuration, channelInitializer, nettyIoHandlerFactory, connectionRegistry);
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
    void shouldNotWaitOutGracePeriodForIdleConnections() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        nettyServer = newServer(null, accepted);
        nettyServer.start();

        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "server must have accepted the connection");

            long startedAt = System.nanoTime();
            NettyShutdownResult result = nettyServer.shutdown(Duration.ofSeconds(5));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            assertEquals(NettyShutdownResult.IDLE, result,
                "an idle connection carries no in-flight request, so nothing is left to drain");
            assertTrue(elapsedMillis < 1_000,
                "shutdown must not wait out the grace period on an idle connection, took " + elapsedMillis + "ms");
        }
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
