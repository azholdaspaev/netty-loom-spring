package io.github.azholdaspaev.nettyloom.autoconfigure.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;

public class NettyWebServerFactory extends AbstractServletWebServerFactory {

    private static final InetAddress WILDCARD_ADDRESS;

    static {
        try {
            WILDCARD_ADDRESS = InetAddress.getByName("0.0.0.0");
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        InetAddress address = Objects.requireNonNullElse(getAddress(), WILDCARD_ADDRESS);
        return new NettyWebServer(address, getPort(), initializers);
    }
}
