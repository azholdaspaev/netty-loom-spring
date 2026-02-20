package io.github.azholdaspaev.nettyloom.core.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.util.concurrent.atomic.AtomicReference;

public class NettyServer {

    private final NettyServerConfig config;
    private final NettyServerInitializer channelInitializer;
    private final AtomicReference<NettyServerState> state;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyServer(NettyServerConfig config, NettyServerInitializer channelInitializer) {
        this.config = config;
        this.channelInitializer = channelInitializer;
        this.state = new AtomicReference<>(NettyServerState.CREATED);
    }

    public void start() {
        if (!state.compareAndSet(NettyServerState.CREATED, NettyServerState.STARTING)) {
            return;
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
                    .childHandler(channelInitializer);

            ChannelFuture bindFuture = bootstrap.bind(config.port()).sync();
            serverChannel = bindFuture.channel();

            state.set(NettyServerState.RUNNING);
        } catch (Exception e) {
            state.set(NettyServerState.STOPPED);
            shutdown(bossGroup, workerGroup);
            throw new IllegalStateException(e);
        }
    }

    public void stop() {
        if (!state.compareAndSet(NettyServerState.RUNNING, NettyServerState.STOPPING)) {
            return;
        }

        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }

            shutdown(bossGroup, workerGroup);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            state.set(NettyServerState.STOPPED);
        }
    }

    private void shutdown(EventLoopGroup... groups) {
        for (EventLoopGroup group : groups) {
            if (group != null) {
                group.shutdownGracefully();
            }
        }
    }

    public int getPort() {
        if (serverChannel != null && serverChannel.localAddress() instanceof java.net.InetSocketAddress addr) {
            return addr.getPort();
        }
        return config.port();
    }
}
