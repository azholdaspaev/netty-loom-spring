package io.azholdaspaev.nettyloom.autoconfigure.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("server.netty")
public record NettyLoomProperties(
    @DefaultValue("0") int port,
    @DefaultValue("1") int bossThreads,
    @DefaultValue("0") int workerThreads,
    @DefaultValue("true") boolean keepAlive
) {}
