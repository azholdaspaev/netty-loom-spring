package io.github.azholdaspaev.nettyloom.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NettyServerConfigTest {

    @Test
    void shouldUseDefaultValues() {
        // When
        NettyServerConfig config = NettyServerConfig.builder().build();

        // Then
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.bossThreads()).isEqualTo(1);
        assertThat(config.workerThreads()).isZero();
        assertThat(config.maxInitialLineLength()).isEqualTo(4096);
        assertThat(config.maxHeaderSize()).isEqualTo(8192);
        assertThat(config.maxChunkSize()).isEqualTo(8192);
        assertThat(config.maxContentLength()).isEqualTo(2097152);
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void shouldOverrideAllDefaults() {
        // When
        NettyServerConfig config = NettyServerConfig.builder()
                .port(9090)
                .bossThreads(2)
                .workerThreads(4)
                .maxInitialLineLength(2048)
                .maxHeaderSize(4096)
                .maxChunkSize(4096)
                .maxContentLength(1048576)
                .idleTimeout(Duration.ofSeconds(30))
                .build();

        // Then
        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.bossThreads()).isEqualTo(2);
        assertThat(config.workerThreads()).isEqualTo(4);
        assertThat(config.maxInitialLineLength()).isEqualTo(2048);
        assertThat(config.maxHeaderSize()).isEqualTo(4096);
        assertThat(config.maxChunkSize()).isEqualTo(4096);
        assertThat(config.maxContentLength()).isEqualTo(1048576);
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void shouldOverrideSingleFieldAndKeepOtherDefaults() {
        // When
        NettyServerConfig config = NettyServerConfig.builder().port(3000).build();

        // Then
        assertThat(config.port()).isEqualTo(3000);
        assertThat(config.bossThreads()).isEqualTo(1);
        assertThat(config.workerThreads()).isZero();
        assertThat(config.maxContentLength()).isEqualTo(2097152);
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofSeconds(60));
    }
}
