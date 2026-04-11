package io.azholdaspaev.nettyloom.autoconfigure.server;

import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;

public class NettyWebServerFactory implements ServletWebServerFactory {

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        return null;
    }
}
