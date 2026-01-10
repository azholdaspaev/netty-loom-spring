package io.github.azholdaspaev.nettyloom.autoconfigure.server;

import io.github.azholdaspaev.nettyloom.core.server.NettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot {@link WebServer} implementation that wraps a {@link NettyServer}.
 *
 * <p>This class adapts the Netty server lifecycle to Spring Boot's WebServer interface,
 * providing:
 * <ul>
 *   <li>Start/stop lifecycle management</li>
 *   <li>Port information retrieval</li>
 *   <li>Graceful shutdown support</li>
 * </ul>
 */
public class NettyWebServer implements WebServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebServer.class);

    private final NettyServer nettyServer;
    private final Duration shutdownTimeout;
    private volatile boolean started = false;

    /**
     * Creates a new NettyWebServer wrapping the given server.
     *
     * @param nettyServer the underlying Netty server
     * @param shutdownTimeout the timeout for graceful shutdown
     */
    public NettyWebServer(NettyServer nettyServer, Duration shutdownTimeout) {
        this.nettyServer = nettyServer;
        this.shutdownTimeout = shutdownTimeout != null ? shutdownTimeout : Duration.ofSeconds(30);
    }

    /**
     * Creates a new NettyWebServer with default shutdown timeout.
     *
     * @param nettyServer the underlying Netty server
     */
    public NettyWebServer(NettyServer nettyServer) {
        this(nettyServer, Duration.ofSeconds(30));
    }

    @Override
    public void start() throws WebServerException {
        if (started) {
            return;
        }

        try {
            nettyServer.start();
            started = true;
            logger.info("Netty web server started on port {}", getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebServerException("Failed to start Netty server", e);
        } catch (Exception e) {
            throw new WebServerException("Failed to start Netty server", e);
        }
    }

    @Override
    public void stop() throws WebServerException {
        if (!started) {
            return;
        }

        try {
            nettyServer.stop();
            started = false;
            logger.info("Netty web server stopped");
        } catch (Exception e) {
            throw new WebServerException("Failed to stop Netty server", e);
        }
    }

    @Override
    public int getPort() {
        int port = nettyServer.getPort();
        return port > 0 ? port : -1;
    }

    @Override
    public void shutDownGracefully(GracefulShutdownCallback callback) {
        logger.info("Initiating graceful shutdown with timeout: {}", shutdownTimeout);

        try {
            // Stop accepting new connections and wait for in-flight requests
            nettyServer.stop(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
            started = false;

            // Notify Spring Boot that shutdown completed successfully
            callback.shutdownComplete(GracefulShutdownResult.IDLE);
            logger.info("Graceful shutdown completed");
        } catch (Exception e) {
            logger.error("Error during graceful shutdown", e);
            callback.shutdownComplete(GracefulShutdownResult.REQUESTS_ACTIVE);
        }
    }

    /**
     * Returns whether the server is currently running.
     *
     * @return true if started and not stopped
     */
    public boolean isRunning() {
        return started && nettyServer.isRunning();
    }

    /**
     * Returns the underlying NettyServer.
     *
     * @return the Netty server instance
     */
    public NettyServer getNettyServer() {
        return nettyServer;
    }
}
