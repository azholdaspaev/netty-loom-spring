package io.github.azholdaspaev.nettyloom.core.server;

import io.github.azholdaspaev.nettyloom.core.executor.VirtualThreadExecutorFactory;
import io.github.azholdaspaev.nettyloom.core.pipeline.HttpServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Netty-based HTTP server with virtual thread support.
 */
public class NettyServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    private final NettyServerConfiguration config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ExecutorService virtualThreadExecutor;
    private Channel serverChannel;
    private volatile boolean running = false;

    public NettyServer(NettyServerConfiguration config) {
        this.config = config;
    }

    /**
     * Starts the server and binds to the configured port.
     *
     * @throws InterruptedException if the thread is interrupted while starting
     */
    public void start() throws InterruptedException {
        if (running) {
            throw new IllegalStateException("Server is already running");
        }

        int bossThreads = config.getBossThreads();
        int workerThreads = config.getWorkerThreads() > 0
                ? config.getWorkerThreads()
                : Runtime.getRuntime().availableProcessors();

        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup(workerThreads);
        virtualThreadExecutor = VirtualThreadExecutorFactory.create("netty-vt-");

        HttpServerInitializer initializer = new HttpServerInitializer(
                config.getMaxContentLength(),
                virtualThreadExecutor
        );

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(initializer)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        serverChannel = bootstrap.bind(config.getHost(), config.getPort()).sync().channel();
        running = true;

        InetSocketAddress address = (InetSocketAddress) serverChannel.localAddress();
        logger.info("Netty server started on {}:{}", address.getHostString(), address.getPort());
    }

    /**
     * Stops the server gracefully.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        logger.info("Stopping Netty server...");

        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while closing server channel", e);
        }

        shutdownEventLoops();
        shutdownExecutor();

        logger.info("Netty server stopped");
    }

    /**
     * Stops the server with a graceful shutdown period.
     *
     * @param timeout the maximum time to wait for in-flight requests
     * @param unit the time unit
     */
    public void stop(long timeout, TimeUnit unit) {
        if (!running) {
            return;
        }

        running = false;
        logger.info("Gracefully stopping Netty server (timeout: {} {})", timeout, unit);

        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while closing server channel", e);
        }

        long quietPeriod = Math.max(1, unit.toSeconds(timeout) / 4);
        long shutdownTimeout = unit.toSeconds(timeout);

        if (workerGroup != null) {
            workerGroup.shutdownGracefully(quietPeriod, shutdownTimeout, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(quietPeriod, shutdownTimeout, TimeUnit.SECONDS);
        }

        shutdownExecutor();

        logger.info("Netty server stopped");
    }

    private void shutdownEventLoops() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    private void shutdownExecutor() {
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdown();
            try {
                if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    virtualThreadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                virtualThreadExecutor.shutdownNow();
            }
        }
    }

    /**
     * Returns the actual port the server is bound to.
     * Useful when port 0 is configured to get an ephemeral port.
     *
     * @return the bound port, or -1 if not started
     */
    public int getPort() {
        if (serverChannel != null && serverChannel.localAddress() instanceof InetSocketAddress addr) {
            return addr.getPort();
        }
        return -1;
    }

    /**
     * Returns true if the server is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the server configuration.
     *
     * @return the configuration
     */
    public NettyServerConfiguration getConfiguration() {
        return config;
    }
}
