package io.azholdaspaev.nettyloom.core.server;

public record NettyServerConfiguration(
    int port,
    int bossThreads,
    int workerThreads,
    boolean keepAlive
) {}
