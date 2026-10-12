package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * Caps open connections the way Tomcat's {@code maxConnections} does: at the cap the listen socket
 * stops being read, so further clients wait in the kernel backlog rather than being accepted and
 * reset. Sits on the server channel, where each accepted connection arrives before
 * {@code ServerBootstrapAcceptor} registers it.
 */
class NettyMaxConnectionsHandler extends ChannelInboundHandlerAdapter {

    private final int maxConnections;
    private final HttpConnectionRegistry connectionRegistry;

    // Read and written only on the server channel's event loop.
    private final Set<Channel> open = new HashSet<>();

    NettyMaxConnectionsHandler(int maxConnections, HttpConnectionRegistry connectionRegistry) {
        this.maxConnections = maxConnections;
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel child = (Channel) msg;
        open.add(child);
        child.closeFuture().addListener(_ -> releaseOn(ctx, child));
        if (open.size() == maxConnections) {
            ctx.channel().config().setAutoRead(false);
            connectionRegistry.markAcceptPaused();
        }
        ctx.fireChannelRead(msg);
        // ServerBootstrapAcceptor.forceClose uses closeForcibly(), which never completes the child's closeFuture.
        if (!child.isOpen()) {
            release(ctx, child);
        }
    }

    private void releaseOn(ChannelHandlerContext ctx, Channel child) {
        try {
            ctx.executor().execute(() -> release(ctx, child));
        } catch (RejectedExecutionException _) {
            // The server loop has stopped, so there is no listen socket left to resume.
        }
    }

    private void release(ChannelHandlerContext ctx, Channel child) {
        // Only on leaving the cap: ServerBootstrapAcceptor.exceptionCaught pauses accept for its own back-off.
        if (open.remove(child) && open.size() == maxConnections - 1) {
            ctx.channel().config().setAutoRead(true);
        }
    }
}
