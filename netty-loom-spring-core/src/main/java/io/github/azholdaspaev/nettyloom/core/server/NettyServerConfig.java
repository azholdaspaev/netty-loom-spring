package io.github.azholdaspaev.nettyloom.core.server;

import java.time.Duration;

public record NettyServerConfig(
        int port,
        int bossThreads,
        int workerThreads,
        int maxInitialLineLength,
        int maxHeaderSize,
        int maxChunkSize,
        int maxContentLength,
        Duration idleTimeout) {

    public static NettyServerConfigBuilder builder() {
        return new NettyServerConfigBuilder();
    }

    public static class NettyServerConfigBuilder {
        private int port = 8080;
        private int bossThreads = 1;
        private int workerThreads;
        private int maxInitialLineLength = 4096;
        private int maxHeaderSize = 8192;
        private int maxChunkSize = 8192;
        private int maxContentLength = 2097152;
        private Duration idleTimeout = Duration.ofSeconds(60);

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

        public NettyServerConfig build() {
            return new NettyServerConfig(
                    port,
                    bossThreads,
                    workerThreads,
                    maxInitialLineLength,
                    maxHeaderSize,
                    maxChunkSize,
                    maxContentLength,
                    idleTimeout);
        }
    }
}
