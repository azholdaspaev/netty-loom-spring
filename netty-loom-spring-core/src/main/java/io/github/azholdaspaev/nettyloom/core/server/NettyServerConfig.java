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
        private int port;
        private int bossThreads;
        private int workerThreads;
        private int maxInitialLineLength;
        private int maxHeaderSize;
        private int maxChunkSize;
        private int maxContentLength;
        private Duration idleTimeout;

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
