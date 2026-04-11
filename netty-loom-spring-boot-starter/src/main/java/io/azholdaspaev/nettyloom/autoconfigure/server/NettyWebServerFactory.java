package io.azholdaspaev.nettyloom.autoconfigure.server;

import io.azholdaspaev.nettyloom.core.server.NettyServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;

public class NettyWebServerFactory implements ServletWebServerFactory {

    private final NettyServer nettyServer;

    public NettyWebServerFactory(NettyServer nettyServer) {
        this.nettyServer = nettyServer;
    }

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        return new NettyWebServer(nettyServer);
    }
}
