package io.github.azholdaspaev.nettyloomspring.autoconfigure.properties;

import io.github.azholdaspaev.nettyloomspring.core.server.NettyTransportPreference;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("server.netty")
public record NettyLoomProperties(
    @DefaultValue("1") int bossThreads,
    @DefaultValue("0") int workerThreads,
    @DefaultValue("true") boolean tcpKeepAlive,
    @DefaultValue("30s") Duration shutdownGracePeriod,
    @DefaultValue("30s") Duration readTimeout,
    @DefaultValue("60s") Duration writeStallTimeout,
    @DefaultValue("auto") NettyTransportPreference transport
) {}
