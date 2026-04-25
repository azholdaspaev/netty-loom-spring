package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.exception.NettyServerException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class NettyServer {

    private final Object lock = new Object();

    private final NettyServerConfiguration configuration;
    private final NettyServerChannelInitializer channelInitializer;
    private final ChannelGroup channelGroup;

    private volatile RunningState state;

    public NettyServer(NettyServerConfiguration configuration,
                       NettyServerChannelInitializer channelInitializer,
                       ChannelGroup channelGroup) {
        this.configuration = configuration;
        this.channelInitializer = channelInitializer;
        this.channelGroup = channelGroup;
    }

    public void start() {
        synchronized (lock) {
            if (state != null) {
                return;
            }
            EventLoopGroup boss = newEventLoopGroup(configuration.bossThreads());
            EventLoopGroup worker = newEventLoopGroup(configuration.workerThreads());
            boolean bound = false;
            try {
                Channel channel = bind(boss, worker);
                state = new RunningState(channel, boss, worker);
                bound = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NettyServerException("Server start interrupted", e);
            } finally {
                if (!bound) {
                    stopEventLoopsQuietly(boss, worker);
                }
            }
        }
    }

    public NettyShutdownResult shutdown(Duration timeout) {
        synchronized (lock) {
            RunningState current = state;
            if (current == null) {
                return NettyShutdownResult.IDLE;
            }
            state = null;
            Deadline deadline = Deadline.in(timeout);
            try {
                current.serverChannel().close().sync();
                boolean drained = drainOrForceClose(deadline);
                stopEventLoops(deadline, current.bossGroup(), current.workerGroup());
                return drained ? NettyShutdownResult.IDLE : NettyShutdownResult.REQUESTS_ACTIVE;
            } catch (InterruptedException e) {
                stopEventLoopsQuietly(current.bossGroup(), current.workerGroup());
                Thread.currentThread().interrupt();
                throw new NettyServerException("Server shutdown interrupted", e);
            }
        }
    }

    /**
     * Closes the server socket so new connections are refused while keeping running state,
     * allowing in-flight requests to drain. Finish the shutdown by calling
     * {@link #shutdown(Duration)}. While in this drain window {@link #isRunning()} stays true.
     */
    public void stopAcceptingConnections() {
        synchronized (lock) {
            RunningState current = state;
            if (current == null) {
                return;
            }
            try {
                current.serverChannel().close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NettyServerException("Interrupted closing server channel", e);
            }
        }
    }

    public int getPort() {
        InetSocketAddress address = resolvedAddress();
        return address == null ? configuration.port() : address.getPort();
    }

    public boolean isRunning() {
        return state != null;
    }

    private Channel bind(EventLoopGroup boss, EventLoopGroup worker) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(boss, worker)
            .channel(NioServerSocketChannel.class)
            .childHandler(channelInitializer)
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, configuration.keepAlive());
        return bootstrap.bind(configuration.port()).sync().channel();
    }

    private boolean drainOrForceClose(Deadline deadline) throws InterruptedException {
        boolean drained = channelGroup.newCloseFuture().await(deadline.remainingMillis(), TimeUnit.MILLISECONDS);
        if (!drained) {
            channelGroup.close().sync();
        }
        return drained;
    }

    private void stopEventLoops(Deadline deadline, EventLoopGroup... groups) throws InterruptedException {
        var futures = new ArrayList<Future<?>>(groups.length);
        for (EventLoopGroup group : groups) {
            futures.add(group.shutdownGracefully(0, deadline.remainingMillis(), TimeUnit.MILLISECONDS));
        }
        for (var future : futures) {
            future.sync();
        }
    }

    private void stopEventLoopsQuietly(EventLoopGroup... groups) {
        for (EventLoopGroup group : groups) {
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    private static EventLoopGroup newEventLoopGroup(int threads) {
        return new MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory());
    }

    private InetSocketAddress resolvedAddress() {
        RunningState current = state;
        if (current != null && current.serverChannel().localAddress() instanceof InetSocketAddress addr) {
            return addr;
        }
        return null;
    }

    private record RunningState(Channel serverChannel, EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
    }
}
