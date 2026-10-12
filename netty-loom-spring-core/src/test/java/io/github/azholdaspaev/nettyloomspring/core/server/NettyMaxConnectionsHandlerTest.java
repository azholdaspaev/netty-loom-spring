package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.support.StandardError;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyMaxConnectionsHandlerTest {

    private final HttpConnectionRegistry registry =
        new HttpConnectionRegistry(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));

    @Test
    void shouldForwardEveryConnectionBelowCap() {
        EmbeddedChannel server = newServerChannel(3);

        server.writeInbound(new EmbeddedChannel(), new EmbeddedChannel());

        assertEquals(2, server.inboundMessages().size(), "connections below the cap must reach the acceptor");
        assertTrue(server.config().isAutoRead(), "accept must keep running below the cap");
        assertEquals(0, registry.getAcceptPauseCount());
    }

    @Test
    void shouldPauseAcceptOnceCapIsReached() {
        EmbeddedChannel server = newServerChannel(2);

        server.writeInbound(new EmbeddedChannel(), new EmbeddedChannel());

        assertEquals(2, server.inboundMessages().size(), "the connection that reaches the cap is still admitted");
        assertFalse(server.config().isAutoRead(), "accept must pause once the cap is reached");
        assertEquals(1, registry.getAcceptPauseCount(), "reaching the cap must be counted");
    }

    @Test
    void shouldResumeAcceptOnceConnectionClosesAtCap() {
        EmbeddedChannel server = newServerChannel(2);
        EmbeddedChannel first = new EmbeddedChannel();
        server.writeInbound(first, new EmbeddedChannel());

        first.close();
        server.runPendingTasks();

        assertTrue(server.config().isAutoRead(), "a close at the cap must resume accept");
    }

    @Test
    void shouldPauseAgainWhenCapIsReachedAfterRelease() {
        EmbeddedChannel server = newServerChannel(1);
        EmbeddedChannel first = new EmbeddedChannel();
        server.writeInbound(first);
        first.close();
        server.runPendingTasks();

        server.writeInbound(new EmbeddedChannel());

        assertFalse(server.config().isAutoRead(), "a released slot must count again once it is taken");
        assertEquals(2, registry.getAcceptPauseCount());
    }

    @Test
    void shouldLeaveAcceptBackOffAloneBelowCap() {
        EmbeddedChannel server = newServerChannel(2);
        EmbeddedChannel only = new EmbeddedChannel();
        server.writeInbound(only);
        server.config().setAutoRead(false);

        only.close();
        server.runPendingTasks();

        assertFalse(server.config().isAutoRead(),
            "a close below the cap must not cancel a pause it did not make, such as the acceptor's back-off");
    }

    @Test
    void shouldDropReleaseAfterServerLoopStops() throws Exception {
        EventLoopGroup serverLoop = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        LocalServerChannel server = new LocalServerChannel();
        server.pipeline().addLast(new NettyMaxConnectionsHandler(2, registry));
        serverLoop.register(server).sync();
        EmbeddedChannel connection = new EmbeddedChannel();
        server.eventLoop().submit(() -> server.pipeline().fireChannelRead(connection)).sync();
        serverLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();

        String logged = StandardError.captureDuring(connection::close);

        /*
         * DefaultPromise catches and logs whatever a close listener throws, so a release that lets the
         * stopped loop's rejection escape fails only through the log, never through close().
         */
        assertFalse(logged.contains("RejectedExecutionException"),
            "a connection closing after the server loop stopped has nothing to resume; logged: " + logged);
    }

    private EmbeddedChannel newServerChannel(int maxConnections) {
        return new EmbeddedChannel(new NettyMaxConnectionsHandler(maxConnections, registry));
    }
}
