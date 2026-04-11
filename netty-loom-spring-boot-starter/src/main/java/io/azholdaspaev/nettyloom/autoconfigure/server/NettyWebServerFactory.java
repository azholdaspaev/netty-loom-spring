package io.azholdaspaev.nettyloom.autoconfigure.server;

import io.azholdaspaev.nettyloom.core.server.NettyServer;
import io.azholdaspaev.nettyloom.mvc.servlet.NettyServletContext;
import jakarta.servlet.ServletException;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;

public class NettyWebServerFactory implements ServletWebServerFactory {

    private final NettyServer nettyServer;
    private final NettyServletContext servletContext;

    public NettyWebServerFactory(NettyServer nettyServer, NettyServletContext servletContext) {
        this.nettyServer = nettyServer;
        this.servletContext = servletContext;
    }

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        initializeServletContext(initializers);
        return new NettyWebServer(nettyServer);
    }

    private void initializeServletContext(ServletContextInitializer... initializers) {
        for (ServletContextInitializer initializer : initializers) {
            try {
                initializer.onStartup(servletContext);
            } catch (ServletException e) {
                throw new WebServerException("Failed to initialize servlet context", e);
            }
        }
    }
}
