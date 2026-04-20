package io.azholdaspaev.nettyloom.autoconfigure.server;

import io.azholdaspaev.nettyloom.core.server.NettyServer;
import io.azholdaspaev.nettyloom.core.exception.NettyServerException;
import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;

public class NettyWebServer implements WebServer {

    private static final ThreadFactory SHUTDOWN_THREAD_FACTORY =
        Thread.ofPlatform().name("netty-loom-graceful-shutdown").daemon(false).factory();

    private final NettyServer nettyServer;
    private final Duration gracePeriod;

    public NettyWebServer(NettyServer nettyServer, Duration gracePeriod) {
        this.nettyServer = nettyServer;
        this.gracePeriod = gracePeriod;
    }

    @Override
    public void start() throws WebServerException {
        try {
            nettyServer.start();
        } catch (NettyServerException e) {
            throw new WebServerException("Failed to start Netty server", e);
        }
    }

    @Override
    public void stop() throws WebServerException {
        try {
            nettyServer.shutdown(Duration.ZERO);
        } catch (NettyServerException e) {
            throw new WebServerException("Failed to stop Netty server", e);
        }
    }

    @Override
    public void shutDownGracefully(GracefulShutdownCallback callback) {
        nettyServer.stopAcceptingConnections();
        shutdownGracefully(callback);
    }

    private void shutdownGracefully(GracefulShutdownCallback callback) {
        SHUTDOWN_THREAD_FACTORY.newThread(() -> {
            GracefulShutdownResult result = GracefulShutdownResult.REQUESTS_ACTIVE;
            try {
                result = switch (nettyServer.shutdown(gracePeriod)) {
                    case IDLE -> GracefulShutdownResult.IDLE;
                    case REQUESTS_ACTIVE -> GracefulShutdownResult.REQUESTS_ACTIVE;
                };
            } finally {
                callback.shutdownComplete(result);
            }
        }).start();
    }

    @Override
    public int getPort() {
        return nettyServer.getPort();
    }
}
