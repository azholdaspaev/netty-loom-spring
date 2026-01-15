package io.github.azholdaspaev.nettyloom.core.server;

import io.github.azholdaspaev.nettyloom.core.http.HttpNettyServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Netty-based HTTP server with virtual thread support.
 *
 * <p>A request handler must be provided to process HTTP requests.
 * For Spring Boot integration, use SpringMvcBridgeHandler.
 */
public class NettyServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    private final NettyServerConfiguration config;
    private final ChannelHandler requestHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    /**
     * Creates a server with a request handler.
     *
     * @param config the server configuration
     * @param requestHandler the handler to process requests (required)
     * @throws NullPointerException if requestHandler is null
     */
    public NettyServer(NettyServerConfiguration config, ChannelHandler requestHandler) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler must not be null");
    }

    /**
     * Starts the server and binds to the configured port.
     *
     * @throws InterruptedException if the thread is interrupted while starting
     */
    public synchronized void start() throws InterruptedException {
        if (running) {
            throw new IllegalStateException("Server is already running");
        }

        int bossThreads = config.getBossThreads();
        int workerThreads = config.getWorkerThreads() > 0
                ? config.getWorkerThreads()
                : Runtime.getRuntime().availableProcessors();

        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup(workerThreads);

        var serverInitializer = new HttpNettyServerInitializer(
                config.getMaxContentLength(),
                requestHandler
        );

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(serverInitializer)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        serverChannel = bootstrap.bind(config.getHost(), config.getPort())
                .sync()
                .channel();

        running = true;

        InetSocketAddress address = (InetSocketAddress) serverChannel.localAddress();
        logger.info("Netty server started on {}:{}", address.getHostString(), address.getPort());
    }

    /**
     * Stops the server gracefully.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }

        running = false;
        logger.info("Stopping Netty server...");

        closeServerChannel();
        shutdownEventLoops();

        logger.info("Netty server stopped");
    }

    private void closeServerChannel() {
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while closing server channel", e);
        }
    }

    private void shutdownEventLoops() {
        Future<?> workerFuture = null;
        Future<?> bossFuture = null;

        if (workerGroup != null) {
            workerFuture = workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossFuture = bossGroup.shutdownGracefully();
        }

        try {
            if (workerFuture != null) {
                workerFuture.sync();
            }
            if (bossFuture != null) {
                bossFuture.sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while shutting down event loops", e);
        }
    }

    /**
     * Returns the actual port the server is bound to.
     * Useful when port 0 is configured to get an ephemeral port.
     *
     * @return the bound port, or -1 if not started
     */
    public synchronized int getPort() {
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
}
