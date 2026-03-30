package io.github.azholdaspaev.nettyloom.core.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    private final NettyServerConfig config;
    private final NettyServerInitializer initializer;
    private final AtomicReference<NettyServerState> state;

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;

    public NettyServer(NettyServerConfig config, NettyServerInitializer initializer) {
        this.config = config;
        this.initializer = initializer;
        this.state = new AtomicReference<>(NettyServerState.CREATED);
    }

    public void start() {
        if (!state.compareAndSet(NettyServerState.CREATED, NettyServerState.STARTING)) {
            throw new IllegalStateException("Server is already in " + state.get());
        }

        try {
            int workers = config.workerThreads() == 0
                    ? Runtime.getRuntime().availableProcessors() * 2
                    : config.workerThreads();

            bossGroup = new NioEventLoopGroup(config.bossThreads());
            workerGroup = new NioEventLoopGroup(workers);

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(initializer);

            ChannelFuture bindFuture =
                    bootstrap.bind(config.address(), config.port()).sync();
            serverChannel = bindFuture.channel();

            state.set(NettyServerState.RUNNING);
            logger.info("Netty server started on {}:{}", getAddress().getHostAddress(), getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.set(NettyServerState.STOPPED);
            shutdown(false, bossGroup, workerGroup);
            throw new IllegalStateException("Server start interrupted", e);
        } catch (Exception e) {
            state.set(NettyServerState.STOPPED);
            shutdown(false, bossGroup, workerGroup);
            throw new IllegalStateException("Failed to start server", e);
        }
    }

    public void stop() {
        if (!state.compareAndSet(NettyServerState.RUNNING, NettyServerState.STOPPING)) {
            throw new IllegalStateException("Cannot stop server: expected state RUNNING but was " + state.get());
        }

        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }

            shutdown(true, bossGroup, workerGroup);
            logger.info("Netty server stopped");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Server stop interrupted", e);
        } finally {
            state.set(NettyServerState.STOPPED);
        }
    }

    private void shutdown(boolean await, EventLoopGroup... groups) {
        for (EventLoopGroup group : groups) {
            if (group != null) {
                if (await) {
                    group.shutdownGracefully().syncUninterruptibly();
                } else {
                    group.shutdownGracefully();
                }
            }
        }
    }

    public NettyServerState getState() {
        return state.get();
    }

    public int getPort() {
        InetSocketAddress resolved = resolvedAddress();
        return resolved != null ? resolved.getPort() : config.port();
    }

    public InetAddress getAddress() {
        InetSocketAddress resolved = resolvedAddress();
        return resolved != null ? resolved.getAddress() : config.address();
    }

    private InetSocketAddress resolvedAddress() {
        if (state.get() == NettyServerState.RUNNING
                && serverChannel != null
                && serverChannel.localAddress() instanceof InetSocketAddress addr) {
            return addr;
        }
        return null;
    }
}
