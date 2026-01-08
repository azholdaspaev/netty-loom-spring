package io.github.azholdaspaev.nettyloom.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the Netty server.
 *
 * <p>Properties are bound from {@code server.netty.*} in application.properties/yaml.
 *
 * <p>Example configuration:
 * <pre>
 * server.netty.boss-threads=1
 * server.netty.worker-threads=0
 * server.netty.max-content-length=10485760
 * server.netty.shutdown-timeout=30s
 * </pre>
 */
@ConfigurationProperties(prefix = "server.netty")
public class NettyServerProperties {

    /**
     * Number of boss threads for accepting connections.
     * Default is 1, which is sufficient for most applications.
     */
    private int bossThreads = 1;

    /**
     * Number of worker threads for handling I/O.
     * Default is 0, which means use available processors.
     */
    private int workerThreads = 0;

    /**
     * Maximum content length for HTTP request bodies.
     * Default is 10MB.
     */
    private int maxContentLength = 10 * 1024 * 1024;

    /**
     * Connection timeout for new connections.
     */
    private Duration connectionTimeout = Duration.ofSeconds(30);

    /**
     * Idle timeout for connections without activity.
     */
    private Duration idleTimeout = Duration.ofSeconds(60);

    /**
     * Timeout for graceful shutdown.
     * During shutdown, the server will wait this long for in-flight requests to complete.
     */
    private Duration shutdownTimeout = Duration.ofSeconds(30);

    /**
     * Custom Server header value.
     * Set to empty string to disable the Server header.
     */
    private String serverHeader = "Netty-Loom";

    // Getters and setters

    public int getBossThreads() {
        return bossThreads;
    }

    public void setBossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(int maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public String getServerHeader() {
        return serverHeader;
    }

    public void setServerHeader(String serverHeader) {
        this.serverHeader = serverHeader;
    }
}
