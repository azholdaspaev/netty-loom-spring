package io.azholdaspaev.nettyloom.core.server;

public class NettyServerConfiguration {

    private final int port;

    public NettyServerConfiguration(int port) {
        this.port = port;
    }

    public int getPort() {
        return port;
    }
}
