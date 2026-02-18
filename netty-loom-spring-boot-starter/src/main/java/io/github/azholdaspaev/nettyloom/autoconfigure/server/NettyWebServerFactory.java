package io.github.azholdaspaev.nettyloom.autoconfigure.server;

import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;

public class NettyWebServerFactory extends AbstractServletWebServerFactory {

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        return new NettyWebServer(getPort(), initializers);
    }
}
