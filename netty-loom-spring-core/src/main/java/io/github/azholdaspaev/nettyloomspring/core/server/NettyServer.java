package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.exception.NettyServerException;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.util.concurrent.Future;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class NettyServer {

    private final Object lock = new Object();

    private final NettyServerConfiguration configuration;
    private final NettyServerChannelInitializer channelInitializer;
    private final NettyIoHandlerFactory ioHandlerFactory;
    private final HttpConnectionRegistry connectionRegistry;

    private volatile RunningState state;

    public NettyServer(NettyServerConfiguration configuration,
                       NettyServerChannelInitializer channelInitializer,
                       NettyIoHandlerFactory ioHandlerFactory,
                       HttpConnectionRegistry connectionRegistry) {
        this.configuration = configuration;
        this.channelInitializer = channelInitializer;
        this.ioHandlerFactory = ioHandlerFactory;
        this.connectionRegistry = connectionRegistry;
    }

    /**
     * Binds the server socket, starting the event loops. A bind failure is reported as
     * {@link NettyServerException} caused by a {@link BindException} on every transport, carrying the
     * operating system's message unchanged. That type tells a bind failure from any other startup
     * failure — it does not tell a taken port from a denied permission or an unassignable address,
     * since the OS reports all three alike; only the message separates them (issue #74).
     */
    public void start() {
        synchronized (lock) {
            if (state != null) {
                return;
            }
            connectionRegistry.reset();
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
     *
     * <p>Idle connections are closed here rather than waited on: with keep-alive they would
     * otherwise sit open for the whole grace period with no request on them.
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
            connectionRegistry.beginDrain();
        }
    }

    public int getPort() {
        InetSocketAddress address = getBoundAddress();
        return address == null ? configuration.port() : address.getPort();
    }

    public InetSocketAddress getBoundAddress() {
        RunningState current = state;
        if (current != null && current.serverChannel().localAddress() instanceof InetSocketAddress addr) {
            return addr;
        }
        return null;
    }

    public boolean isRunning() {
        return state != null;
    }

    private Channel bind(EventLoopGroup boss, EventLoopGroup worker) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(boss, worker)
            .channel(ioHandlerFactory.getServerChannelClass())
            .childHandler(channelInitializer)
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, configuration.tcpKeepAlive());
        InetSocketAddress address = new InetSocketAddress(configuration.address(), configuration.port());
        ChannelFuture future = bootstrap.bind(address).await();
        if (!future.isSuccess()) {
            throw new NettyServerException("Failed to bind " + address, asBindFailure(future.cause()));
        }
        return future.channel();
    }

    /**
     * Normalizes the bind error, which is otherwise transport-specific: NIO surfaces the JDK's
     * {@link BindException} while epoll and kqueue surface {@code Errors.NativeIoException}, which
     * extends {@link IOException} directly. Since the transport is chosen at runtime, a caller keying
     * off the failure type would work on one platform and not another (issue #68).
     *
     * <p>Every I/O error reachable here comes from creating, binding, or listening on the server
     * socket, so mapping the lot to {@link BindException} matches what the JDK already does for the
     * NIO transport — it reports address-in-use, permission-denied and unassignable-address alike as
     * a bind failure. Non-I/O failures are left alone; they did not come from the socket.
     *
     * <p>The original message is carried over verbatim rather than replaced with a tidier one: the
     * type alone cannot tell those three cases apart, so the {@code strerror} text is the only thing
     * that can, and callers classify by it. Rewording it would drop that discrimination with nothing
     * at this layer to show for it, which is why the test asserts the text still identifies the
     * errno, not just the type (issue #74).
     */
    private static Throwable asBindFailure(Throwable cause) {
        if (cause instanceof IOException && !(cause instanceof BindException)) {
            BindException bindFailure = new BindException(cause.getMessage());
            bindFailure.initCause(cause);
            return bindFailure;
        }
        return cause;
    }

    /**
     * Waits for in-flight requests, not for open sockets: {@link HttpConnectionRegistry#awaitDrained(long)}
     * closes the connections that are idle and marks the rest to close once they have replied, so
     * this completes as soon as the last request is done rather than when a pooling client happens
     * to hang up (issue #67). Returning early is what let the event loops — and the session store
     * torn down above them — go while threads were still serving (issue #108).
     */
    private boolean drainOrForceClose(Deadline deadline) throws InterruptedException {
        boolean drained = connectionRegistry.awaitDrained(deadline.remainingMillis());
        if (!drained) {
            connectionRegistry.closeAll().sync();
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

    private EventLoopGroup newEventLoopGroup(int threads) {
        return new MultiThreadIoEventLoopGroup(threads, ioHandlerFactory.getIoHandlerFactory());
    }

    private record RunningState(Channel serverChannel, EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
    }
}
