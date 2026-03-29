package io.github.azholdaspaev.nettyloom.core.server;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;

public record NettyServerConfig(
        InetAddress address,
        int port,
        int bossThreads,
        int workerThreads,
        int maxInitialLineLength,
        int maxHeaderSize,
        int maxChunkSize,
        int maxContentLength,
        Duration idleTimeout,
        Duration requestTimeout) {

    public NettyServerConfig {
        Objects.requireNonNull(address, "address must not be null");
    }

    public static NettyServerConfigBuilder builder() {
        return new NettyServerConfigBuilder();
    }

    public static class NettyServerConfigBuilder {
        private InetAddress address = InetAddress.getLoopbackAddress();
        private int port = 8080;
        private int bossThreads = 1;
        private int workerThreads;
        private int maxInitialLineLength = 4096;
        private int maxHeaderSize = 8192;
        private int maxChunkSize = 8192;
        private int maxContentLength = 2097152;
        private Duration idleTimeout = Duration.ofSeconds(60);
        private Duration requestTimeout = Duration.ofSeconds(60);

        public NettyServerConfigBuilder address(InetAddress address) {
            this.address = Objects.requireNonNull(address, "address must not be null");
            return this;
        }

        public NettyServerConfigBuilder port(int port) {
            this.port = port;
            return this;
        }

        public NettyServerConfigBuilder bossThreads(int bossThreads) {
            this.bossThreads = bossThreads;
            return this;
        }

        public NettyServerConfigBuilder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public NettyServerConfigBuilder maxInitialLineLength(int maxInitialLineLength) {
            this.maxInitialLineLength = maxInitialLineLength;
            return this;
        }

        public NettyServerConfigBuilder maxHeaderSize(int maxHeaderSize) {
            this.maxHeaderSize = maxHeaderSize;
            return this;
        }

        public NettyServerConfigBuilder maxChunkSize(int maxChunkSize) {
            this.maxChunkSize = maxChunkSize;
            return this;
        }

        public NettyServerConfigBuilder maxContentLength(int maxContentLength) {
            this.maxContentLength = maxContentLength;
            return this;
        }

        public NettyServerConfigBuilder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        public NettyServerConfigBuilder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public NettyServerConfig build() {
            return new NettyServerConfig(
                    address,
                    port,
                    bossThreads,
                    workerThreads,
                    maxInitialLineLength,
                    maxHeaderSize,
                    maxChunkSize,
                    maxContentLength,
                    idleTimeout,
                    requestTimeout);
        }
    }
}
