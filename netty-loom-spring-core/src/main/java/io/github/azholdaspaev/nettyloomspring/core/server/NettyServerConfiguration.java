package io.github.azholdaspaev.nettyloomspring.core.server;

public record NettyServerConfiguration(
    int port,
    int bossThreads,
    int workerThreads,
    boolean keepAlive
) {}
