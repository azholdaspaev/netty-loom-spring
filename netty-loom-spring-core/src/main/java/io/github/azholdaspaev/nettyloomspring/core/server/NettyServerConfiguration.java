package io.github.azholdaspaev.nettyloomspring.core.server;

public record NettyServerConfiguration(
    int port,
    java.net.InetAddress address,
    int bossThreads,
    int workerThreads,
    boolean keepAlive
) {}
