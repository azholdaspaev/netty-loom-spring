package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.exception.NettyServerException;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineStep;
import io.github.azholdaspaev.nettyloomspring.core.support.NettyServerFixture;
import io.github.azholdaspaev.nettyloomspring.core.support.StandardError;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyServerTest {

    private NettyServer nettyServer;

    @BeforeEach
    void setup() {
        nettyServer = newServer(null);
    }

    @Test
    void shouldDeliverInboundBytesToPipelineThatAsksForNoReadsItself() throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        nettyServer = NettyServerFixture.newServer(
            new NettyServerConfiguration(0, null, 1, 1, true, 128),
            new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)),
            List.of(new NettyPipelineStep("capture", () -> new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ByteBuf inbound = (ByteBuf) msg;
                    received.complete(inbound.toString(StandardCharsets.UTF_8));
                    inbound.release();
                }
            })));
        nettyServer.start();

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            client.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

            assertEquals("ping", received.get(5, TimeUnit.SECONDS),
                "a definition replacing the handler list keeps Netty's own read behaviour: manual reads "
                    + "belong to the handlers that issue them");
        }
    }

    @Test
    void shouldApplyAcceptCountToListenSocket() throws Exception {
        CompletableFuture<Integer> backlog = new CompletableFuture<>();
        nettyServer = NettyServerFixture.newServer(
            new NettyServerConfiguration(0, null, 1, 1, false, 7),
            new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)),
            List.of(new NettyPipelineStep("backlog", () -> new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    backlog.complete(ctx.channel().parent().config().getOption(ChannelOption.SO_BACKLOG));
                    ctx.fireChannelActive();
                }
            })));
        nettyServer.start();

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);

            assertEquals(7, backlog.get(5, TimeUnit.SECONDS),
                "the configured accept count must reach the listen socket's SO_BACKLOG option");
        }
    }

    private static NettyServer newServer(InetAddress address) {
        return newServer(address, null, 0);
    }

    /**
     * @param accepted counted down on accept, so a test acts on a connection the server knows about
     * @param port     0 to let the OS choose; a specific port to bind exactly there
     */
    private static NettyServer newServer(
        InetAddress address, CountDownLatch accepted, int port, NettyPipelineStep... extra) {
        return newServer(address, accepted, port, new NettyIoHandlerFactory(NettyTransportPreference.AUTO), extra);
    }

    private static NettyServer newServer(InetAddress address, CountDownLatch accepted, int port,
                                         NettyIoHandlerFactory ioHandlerFactory, NettyPipelineStep... extra) {
        NettyServerConfiguration configuration = new NettyServerConfiguration(port, address, 0, 0, false, 128);
        List<NettyPipelineStep> handlers = new ArrayList<>();
        if (accepted != null) {
            handlers.add(new NettyPipelineStep("accepted", () -> new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    accepted.countDown();
                    ctx.fireChannelActive();
                }
            }));
        }
        handlers.addAll(List.of(extra));
        HttpConnectionRegistry connectionRegistry = new HttpConnectionRegistry(
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
        return NettyServerFixture.newServer(configuration, connectionRegistry, handlers, ioHandlerFactory);
    }

    private static NettyIoHandlerFactory newCloseFailingListenerTransport() {
        return new NettyIoHandlerFactory(NettyTransportPreference.NIO) {
            @Override
            public Class<? extends ServerChannel> getServerChannelClass() {
                return CloseFailingServerChannel.class;
            }
        };
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
    void shouldReportBindFailureAsBindExceptionOnEveryTransport() throws Exception {
        // Held open for the whole attempt so the bind is guaranteed to collide.
        try (ServerSocket squatter = new ServerSocket(0)) {
            nettyServer = newServer(null, null, squatter.getLocalPort());

            NettyServerException failure = assertThrows(NettyServerException.class, nettyServer::start);

            assertInstanceOf(BindException.class, failure.getCause());
            /*
             * The type is the same for a taken port, a denied permission and an unassignable address,
             * so the OS text is the only thing that separates them. Boot's PortInUseException matches
             * message.toLowerCase(Locale.ROOT).contains("in use") -- named here because the literal
             * below is its rule, and core carries no Spring dependency to point at.
             * Normalizing the type must not normalize the message away.
             */
            assertTrue(failure.getCause().getMessage().toLowerCase(Locale.ROOT).contains("in use"),
                "bind failure should carry the OS text; was: " + failure.getCause().getMessage());
        }
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
        nettyServer = newServer(null, accepted, 0);
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
    void shouldReportIdleWithNoGraceWhileConnectionIsOpen() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        nettyServer = newServer(null, accepted, 0);
        nettyServer.start();

        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "server must have accepted the connection");

            assertEquals(NettyShutdownResult.IDLE, nettyServer.shutdown(Duration.ZERO),
                "an open connection carrying no request is nothing to drain, so no grace is needed");
        }
    }

    @Test
    void shouldFinishShutdownWhenConnectionFailsToClose() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        nettyServer = newServer(null, accepted, 0,
            new NettyPipelineStep("unclosable", () -> new ChannelOutboundHandlerAdapter() {
                @Override
                public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                    promise.setFailure(new IOException("close failed"));
                }
            }));
        nettyServer.start();

        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "server must have accepted the connection");

            assertEquals(NettyShutdownResult.IDLE, nettyServer.shutdown(Duration.ZERO),
                "a connection that fails to close must not abort the shutdown before the event loops stop");
        }
    }

    @Test
    void shouldFinishShutdownWhenConnectionNeverCloses() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        nettyServer = newServer(null, accepted, 0,
            new NettyPipelineStep("closeNeverCompletes", () -> new ChannelOutboundHandlerAdapter() {
                @Override
                public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                }
            }));
        nettyServer.start();

        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "server must have accepted the connection");

            assertEquals(NettyShutdownResult.IDLE,
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> nettyServer.shutdown(Duration.ZERO)),
                "a close that never completes must not hold the shutdown past its grace period");
        }
    }

    @Test
    void shouldCloseStuckConnectionWhenWorkerLoopIsBusyAtShutdown() throws Exception {
        CompletableFuture<Channel> accepted = new CompletableFuture<>();
        nettyServer = newServerHoldingClose(accepted);
        nettyServer.start();

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            occupyUntilShutdown(accepted.get(5, TimeUnit.SECONDS).eventLoop());

            nettyServer.shutdown(Duration.ZERO);

            client.setSoTimeout(5_000);
            assertEquals(-1, client.getInputStream().read(),
                "a connection whose close is held must still be closed by a shutdown racing a busy worker loop");
        }
    }

    @Test
    void shouldCloseStuckConnectionWhenShutdownIsInterrupted() throws Exception {
        CompletableFuture<Channel> accepted = new CompletableFuture<>();
        nettyServer = newServerHoldingClose(accepted);
        nettyServer.start();

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            occupyUntilShutdown(accepted.get(5, TimeUnit.SECONDS).eventLoop());

            /*
             * A non-zero grace: under Duration.ZERO, Netty's await(0) returns before it reads the interrupt, so
             * the shutdown is interrupted only in stopEventLoops, after drainThenClose has force-closed already.
             */
            Thread.currentThread().interrupt();
            try {
                assertThrows(NettyServerException.class, () -> nettyServer.shutdown(Duration.ofSeconds(5)));
            } finally {
                Thread.interrupted();
            }

            client.setSoTimeout(5_000);
            assertEquals(-1, client.getInputStream().read(),
                "a connection whose close is held must still be closed by an interrupted shutdown");
        }
    }

    private static NettyServer newServerHoldingClose(CompletableFuture<Channel> accepted) {
        return newServer(null, null, 0,
            new NettyPipelineStep("closeNeverCompletes", () -> new ChannelOutboundHandlerAdapter() {
                @Override
                public void handlerAdded(ChannelHandlerContext ctx) {
                    accepted.complete(ctx.channel());
                }

                @Override
                public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                }
            }));
    }

    private static void occupyUntilShutdown(EventLoop loop) throws InterruptedException {
        CountDownLatch spinning = new CountDownLatch(1);
        loop.execute(() -> {
            spinning.countDown();
            while (!loop.isShuttingDown()) {
                Thread.onSpinWait();
            }
        });
        /*
         * Without the wait, the shutdown flag can land before the spin starts: the loop's next iteration
         * then runs prepareToDestroy, which closes the connection and passes the test against broken code.
         */
        assertTrue(spinning.await(5, TimeUnit.SECONDS), "the worker loop must be running the task as shutdown starts");
    }

    @Test
    void shouldReleaseBossGroupWhenWorkerGroupFailsToBuild() throws Exception {
        List<EventExecutor> bossLoops = new CopyOnWriteArrayList<>();
        nettyServer = newServerFailingWorkerGroup(bossLoops, _ -> {
            throw new IllegalStateException("too many open files");
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class, nettyServer::start);

        assertEquals("too many open files", rootCause(failure).getMessage(),
            "the worker group's own failure must be what start() reports");
        assertReleased(bossLoops);
    }

    @Test
    void shouldReleaseBossGroupWhenWorkerGroupFailsWithError() throws Exception {
        List<EventExecutor> bossLoops = new CopyOnWriteArrayList<>();
        OutOfMemoryError exhausted = new OutOfMemoryError("Java heap space");
        nettyServer = newServerFailingWorkerGroup(bossLoops, _ -> {
            throw exhausted;
        });

        OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, nettyServer::start);

        assertSame(exhausted, failure, "the worker group's own failure must be what start() reports");
        assertReleased(bossLoops);
    }

    private static NettyServer newServerFailingWorkerGroup(List<EventExecutor> bossLoops, IoHandlerFactory worker) {
        NettyIoHandlerFactory failing = new NettyIoHandlerFactory(NettyTransportPreference.NIO) {
            private boolean bossBuilt;

            @Override
            public IoHandlerFactory getIoHandlerFactory() {
                if (bossBuilt) {
                    return worker;
                }
                bossBuilt = true;
                IoHandlerFactory real = super.getIoHandlerFactory();
                return executor -> {
                    bossLoops.add((EventExecutor) executor);
                    return real.newHandler(executor);
                };
            }
        };
        return NettyServerFixture.newServer(
            new NettyServerConfiguration(0, InetAddress.getLoopbackAddress(), 1, 1, false, 128),
            new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)),
            List.of(), failing);
    }

    private static void assertReleased(List<EventExecutor> bossLoops) throws InterruptedException {
        assertFalse(bossLoops.isEmpty(), "the boss group must have been built before the worker group failed");
        for (EventExecutor loop : bossLoops) {
            assertTrue(loop.awaitTermination(5, TimeUnit.SECONDS),
                "the boss group's selector must be released when the worker group cannot be built");
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Test
    void shouldStopEventLoopsWhenServerChannelFailsToClose() throws Exception {
        CompletableFuture<List<EventLoop>> eventLoops = new CompletableFuture<>();
        nettyServer = newServer(InetAddress.getLoopbackAddress(), null, 0, newCloseFailingListenerTransport(),
            new NettyPipelineStep("eventLoops", () -> new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    eventLoops.complete(List.of(ctx.channel().parent().eventLoop(), ctx.channel().eventLoop()));
                    ctx.fireChannelActive();
                }
            }));
        nettyServer.start();

        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            List<EventLoop> loops = eventLoops.get(5, TimeUnit.SECONDS);

            assertEquals(NettyShutdownResult.IDLE, nettyServer.shutdown(Duration.ofSeconds(5)),
                "a listening socket that fails to close must not abort the shutdown");
            assertTrue(loops.stream().allMatch(EventLoop::isTerminated),
                "the boss and worker event loops must stop although the listening socket failed to close");
        }
    }

    @Test
    void shouldBeginDrainWhenServerChannelFailsToClose() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        nettyServer = newServer(InetAddress.getLoopbackAddress(), accepted, 0, newCloseFailingListenerTransport());
        nettyServer.start();

        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "server must have accepted the connection");

            assertDoesNotThrow(nettyServer::stopAcceptingConnections,
                "a listening socket that fails to close must not escape into the caller");
            idle.setSoTimeout(5_000);
            assertEquals(-1, idle.getInputStream().read(),
                "the drain must still begin and close the idle keep-alive connection");
        }
    }

    @Test
    void shouldLogCauseWhenServerChannelFailsToClose() throws Exception {
        nettyServer = newServer(InetAddress.getLoopbackAddress(), null, 0, newCloseFailingListenerTransport());
        nettyServer.start();

        String logged = StandardError.captureDuring(() -> nettyServer.stopAcceptingConnections());

        assertTrue(logged.contains("WARN") && logged.contains("close failed"),
            "a listening socket that fails to close must leave its cause in the log; logged: " + logged);
    }

    @Test
    void shouldAcceptConnectionsAgainAfterRestart() throws Exception {
        nettyServer.start();
        nettyServer.shutdown(Duration.ofSeconds(1));

        nettyServer.start();

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", nettyServer.getPort()), 1_000);
            client.setSoTimeout(300);
            assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read(),
                "a restarted server must not treat new connections as leftovers of the previous drain");
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

    public static final class CloseFailingServerChannel extends NioServerSocketChannel {

        @Override
        protected void doClose() throws Exception {
            // Closes the socket before failing, so the test does not leak a listening port.
            super.doClose();
            throw new IOException("close failed");
        }
    }
}
