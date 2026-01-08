package io.github.azholdaspaev.nettyloom.core.server;

/**
 * Immutable configuration for the Netty HTTP server.
 */
public final class NettyServerConfiguration {

    private final int port;
    private final String host;
    private final int bossThreads;
    private final int workerThreads;
    private final int maxContentLength;

    private NettyServerConfiguration(int port, String host, int bossThreads,
                                     int workerThreads, int maxContentLength) {
        this.port = port;
        this.host = host;
        this.bossThreads = bossThreads;
        this.workerThreads = workerThreads;
        this.maxContentLength = maxContentLength;
    }

    public int getPort() {
        return port;
    }

    public String getHost() {
        return host;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int port = 8080;
        private String host = "0.0.0.0";
        private int bossThreads = 1;
        private int workerThreads = 0; // 0 means use available processors
        private int maxContentLength = 10 * 1024 * 1024; // 10 MB

        private Builder() {
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder bossThreads(int bossThreads) {
            this.bossThreads = bossThreads;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder maxContentLength(int maxContentLength) {
            this.maxContentLength = maxContentLength;
            return this;
        }

        public NettyServerConfiguration build() {
            return new NettyServerConfiguration(port, host, bossThreads,
                    workerThreads, maxContentLength);
        }
    }
}
