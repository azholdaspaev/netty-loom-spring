package io.github.azholdaspaev.nettyloomspring.core.server;

import io.github.azholdaspaev.nettyloomspring.core.exception.NettyServerException;
import io.github.azholdaspaev.nettyloomspring.core.handler.Durations;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class NettyServer {

    private final Object lock = new Object();

    private final NettyServerConfiguration configuration;
    private final NettyServerChannelInitializer channelInitializer;
    private final NettyIoHandlerFactory ioHandlerFactory;
    private final HttpConnectionRegistry connectionRegistry;

    private volatile State state;

    private Shutdown shutdown;

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
     * {@link NettyServerException} caused by a {@link BindException} on every transport, carrying
     * the operating system's message unchanged — the type separates a bind failure from any other
     * startup failure, and only the message separates the errnos within it (issue #74).
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
                state = new State(channel, boss, worker);
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

    /**
     * The first caller owns the drain and holds {@code lock} only for the handover, never for the
     * wait. A concurrent caller joins that drain, cuts it short once its own {@code timeout} is up
     * and returns the owner's result. {@code state} stays set until the loops are stopped rather
     * than moving into {@link Shutdown}, so {@link #isRunning()} reads true through the drain and
     * {@link #start()} stays a no-op instead of binding a second server into the shared registry.
     */
    public NettyShutdownResult shutdown(Duration timeout) {
        Deadline deadline = Deadline.in(timeout);
        State current;
        Shutdown inProgress;
        Shutdown owned = null;
        synchronized (lock) {
            current = state;
            if (current == null) {
                return NettyShutdownResult.IDLE;
            }
            inProgress = shutdown;
            if (inProgress == null) {
                shutdown = owned = new Shutdown();
            }
        }
        return owned != null ? drainAndStop(current, owned, deadline) : joinOrAbort(inProgress, deadline);
    }

    /**
     * Closes the server socket so new connections are refused, and starts the drain so idle
     * keep-alive connections close now rather than sit open for the whole grace period; finish by
     * calling {@link #shutdown(Duration)}.
     */
    public void stopAcceptingConnections() {
        synchronized (lock) {
            State current = state;
            if (current == null) {
                return;
            }
            closeServerChannel(current);
            connectionRegistry.beginDrain();
        }
    }

    public int getPort() {
        InetSocketAddress address = getBoundAddress();
        return address == null ? configuration.port() : address.getPort();
    }

    public InetSocketAddress getBoundAddress() {
        State current = state;
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
     * NIO surfaces {@link BindException} where the native transports surface a plain
     * {@link IOException} (#68). The message is carried verbatim: it is what separates errnos (#74).
     */
    private static Throwable asBindFailure(Throwable cause) {
        if (cause instanceof IOException && !(cause instanceof BindException)) {
            BindException bindFailure = new BindException(cause.getMessage());
            bindFailure.initCause(cause);
            return bindFailure;
        }
        return cause;
    }

    private NettyShutdownResult drainAndStop(State current, Shutdown owned, Deadline deadline) {
        try {
            closeServerChannel(current);
            boolean drained = drainOrForceClose(deadline);
            stopEventLoops(deadline, current.bossGroup(), current.workerGroup());
            owned.result = drained ? NettyShutdownResult.IDLE : NettyShutdownResult.REQUESTS_ACTIVE;
            return owned.result;
        } catch (InterruptedException e) {
            stopEventLoopsQuietly(current.bossGroup(), current.workerGroup());
            Thread.currentThread().interrupt();
            throw new NettyServerException("Server shutdown interrupted", e);
        } finally {
            synchronized (lock) {
                state = null;
                shutdown = null;
            }
            owned.done.countDown();
        }
    }

    private NettyShutdownResult joinOrAbort(Shutdown inProgress, Deadline deadline) {
        try {
            if (!inProgress.done.await(deadline.remainingMillis(), TimeUnit.MILLISECONDS)) {
                /*
                 * Under lock, and only while this is still the live drain: the owner clears shutdown
                 * and start() resets the registry under the same lock, so a late abort cannot reach
                 * a restarted server. abortDrain does not block, so holding lock across it is cheap.
                 */
                synchronized (lock) {
                    if (shutdown == inProgress) {
                        connectionRegistry.abortDrain();
                    }
                }
                inProgress.done.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NettyServerException("Server shutdown interrupted", e);
        }
        return inProgress.result;
    }

    private static void closeServerChannel(State current) {
        current.serverChannel().close().syncUninterruptibly();
    }

    /**
     * Waits for in-flight requests, not open sockets, so this completes when the last request is
     * done rather than when a pooling client hangs up (issues #67, #108).
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

    private record State(Channel serverChannel, EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
    }

    private record Deadline(long startNanos, long budgetNanos) {

        private static Deadline in(Duration timeout) {
            return new Deadline(System.nanoTime(), Durations.toNanosSaturated(timeout));
        }

        private long remainingMillis() {
            return Math.max(0L, (budgetNanos - (System.nanoTime() - startNanos)) / 1_000_000L);
        }
    }

    private static final class Shutdown {

        private final CountDownLatch done = new CountDownLatch(1);

        // Stays REQUESTS_ACTIVE if the owner throws, so a joiner reads "not drained" rather than nothing.
        private volatile NettyShutdownResult result = NettyShutdownResult.REQUESTS_ACTIVE;
    }
}
