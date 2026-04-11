package io.azholdaspaev.nettyloom.core.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.util.ArrayList;

public class NettyServer {

    private final Object lock = new Object();

    private final NettyServerConfiguration configuration;
    private final NettyServerChannelInitializer channelInitializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private volatile Channel serverChannel;
    private volatile boolean state = false;

    public NettyServer(NettyServerConfiguration configuration, NettyServerChannelInitializer channelInitializer) {
        this.configuration = configuration;
        this.channelInitializer = channelInitializer;
    }

    public void start() {
        synchronized (lock) {
            if (state) {
                return;
            }

            bossGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
            workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(channelInitializer)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            try {
                ChannelFuture bindFuture = bootstrap.bind(configuration.getPort()).sync();
                serverChannel = bindFuture.channel();
                state = true;
            } catch (InterruptedException e) {
                try {
                    shutdownGracefully(bossGroup, workerGroup);
                } catch (InterruptedException suppressed) {
                    e.addSuppressed(suppressed);
                } finally {
                    bossGroup = null;
                    workerGroup = null;
                }
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            if (!state) {
                return;
            }
            try {
                if (serverChannel != null) {
                    serverChannel.close().sync();
                }

                shutdownGracefully(bossGroup, workerGroup);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Server stop interrupted", e);
            } finally {
                serverChannel = null;
                bossGroup = null;
                workerGroup = null;
                state = false;
            }
        }
    }

    private void shutdownGracefully(EventLoopGroup... groups) throws InterruptedException {
        var futures = new ArrayList<Future<?>>();
        for (EventLoopGroup group : groups) {
            if (group != null) {
                futures.add(group.shutdownGracefully());
            }
        }
        for (var future : futures) {
            future.sync();
        }
    }

    public int getPort() {
        InetSocketAddress address = resolvedAddress();
        return address == null ? configuration.getPort() : address.getPort();
    }

    public boolean isRunning() {
        return state;
    }

    private InetSocketAddress resolvedAddress() {
        Channel ch = serverChannel;
        if (ch != null && ch.localAddress() instanceof InetSocketAddress addr) {
            return addr;
        }
        return null;
    }
}
