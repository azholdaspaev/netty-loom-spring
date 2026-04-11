package io.azholdaspaev.nettyloom.autoconfigure.server;

import io.azholdaspaev.nettyloom.core.server.NettyServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;

public class NettyWebServer implements WebServer {

    private final NettyServer nettyServer;

    public NettyWebServer(NettyServer nettyServer) {
        this.nettyServer = nettyServer;
    }

    @Override
    public void start() throws WebServerException {
        nettyServer.start();
    }

    @Override
    public void stop() throws WebServerException {
        nettyServer.stop();
    }

    @Override
    public int getPort() {
        return nettyServer.getPort();
    }
}
