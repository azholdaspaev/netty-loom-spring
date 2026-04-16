package io.azholdaspaev.nettyloom.autoconfigure.server;

import io.azholdaspaev.nettyloom.core.server.NettyServer;
import io.azholdaspaev.nettyloom.mvc.servlet.NettyServletConfig;
import io.azholdaspaev.nettyloom.mvc.servlet.NettyServletContext;
import jakarta.servlet.ServletException;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.web.servlet.DispatcherServlet;

public class NettyWebServerFactory implements ServletWebServerFactory {

    private final NettyServer nettyServer;
    private final NettyServletContext servletContext;
    private final DispatcherServlet dispatcherServlet;

    public NettyWebServerFactory(NettyServer nettyServer,
                                 NettyServletContext servletContext,
                                 DispatcherServlet dispatcherServlet) {
        this.nettyServer = nettyServer;
        this.servletContext = servletContext;
        this.dispatcherServlet = dispatcherServlet;
    }

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        initializeServletContext(initializers);
        initializeDispatcherServlet();
        return new NettyWebServer(nettyServer);
    }

    private void initializeServletContext(ServletContextInitializer... initializers) {
        for (ServletContextInitializer initializer : initializers) {
            try {
                initializer.onStartup(servletContext);
            } catch (ServletException e) {
                throw new WebServerException("Failed to run servlet context initializer", e);
            }
        }
    }

    private void initializeDispatcherServlet() {
        try {
            String servletName = DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME;
            dispatcherServlet.init(new NettyServletConfig(servletName, servletContext));
        } catch (ServletException e) {
            throw new WebServerException("Failed to initialize dispatcher servlet", e);
        }
    }
}
