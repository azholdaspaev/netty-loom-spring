package io.github.azholdaspaev.nettyloomspring.core.server;

import java.net.InetAddress;

public record NettyServerConfiguration(
    int port,
    InetAddress address,
    int bossThreads,
    int workerThreads,
    boolean tcpKeepAlive,
    int acceptCount,
    int maxConnections
) {

    public static final int UNLIMITED_CONNECTIONS = -1;

    public NettyServerConfiguration {
        if (maxConnections < 1 && maxConnections != UNLIMITED_CONNECTIONS) {
            throw new IllegalArgumentException(
                "maxConnections must be positive, or " + UNLIMITED_CONNECTIONS + " for no limit: " + maxConnections);
        }
    }
}
