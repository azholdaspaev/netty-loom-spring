package io.github.azholdaspaev.nettyloomspring.autoconfigure.properties;

import io.github.azholdaspaev.nettyloomspring.core.server.NettyTransportPreference;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties("server.netty")
public record NettyLoomProperties(
    @DefaultValue("1") int bossThreads,
    @DefaultValue("0") int workerThreads,
    @DefaultValue("true") boolean tcpKeepAlive,
    @DefaultValue("128") int acceptCount,
    @DefaultValue("30s") Duration shutdownGracePeriod,
    @DefaultValue("30s") Duration readTimeout,
    @DefaultValue("60s") Duration writeStallTimeout,
    @DefaultValue("1MB") DataSize maxHttpBodySize,
    @DefaultValue("10000B") DataSize maxHeaderSize,
    @DefaultValue("10000B") DataSize maxInitialLineLength,
    @DefaultValue("10000B") DataSize maxChunkSize,
    @DefaultValue("auto") NettyTransportPreference transport
) {}
