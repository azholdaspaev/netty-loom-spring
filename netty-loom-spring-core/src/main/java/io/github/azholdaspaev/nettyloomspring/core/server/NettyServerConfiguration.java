package io.github.azholdaspaev.nettyloomspring.core.server;

import java.net.InetAddress;

public record NettyServerConfiguration(
    int port,
    InetAddress address,
    int bossThreads,
    int workerThreads,
    boolean tcpKeepAlive
) {}
