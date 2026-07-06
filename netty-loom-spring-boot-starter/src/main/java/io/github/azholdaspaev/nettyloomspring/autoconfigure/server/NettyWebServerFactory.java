package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.properties.NettyLoomProperties;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyIoHandlerFactory;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerConfiguration;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyFilterConfig;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletConfig;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.RegisteredFilter;
import io.netty.channel.group.ChannelGroup;
import jakarta.servlet.ServletException;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.servlet.ServletWebServerSettings;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.ArrayList;
import java.util.List;

public class NettyWebServerFactory extends AbstractConfigurableWebServerFactory
    implements ConfigurableServletWebServerFactory {

    private final ServletWebServerSettings settings = new ServletWebServerSettings();

    private final NettyIoHandlerFactory ioHandlerFactory;
    private final NettyServerChannelInitializer channelInitializer;
    private final ChannelGroup channelGroup;
    private final NettyServletContext servletContext;
    private final DispatcherServlet dispatcherServlet;
    private final NettyLoomProperties properties;

    public NettyWebServerFactory(NettyIoHandlerFactory ioHandlerFactory,
                                 NettyServerChannelInitializer channelInitializer,
                                 ChannelGroup channelGroup,
                                 NettyServletContext servletContext,
                                 DispatcherServlet dispatcherServlet,
                                 NettyLoomProperties properties) {
        this.ioHandlerFactory = ioHandlerFactory;
        this.channelInitializer = channelInitializer;
        this.channelGroup = channelGroup;
        this.servletContext = servletContext;
        this.dispatcherServlet = dispatcherServlet;
        this.properties = properties;
    }

    @Override
    public ServletWebServerSettings getSettings() {
        return settings;
    }

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        verifySslNotConfigured();
        // Set the context path before the initializer/filter/servlet startup phases so any component
        // that reads ServletContext.getContextPath() during onStartup/init sees the configured value,
        // as the Jakarta contract requires (rather than the default "").
        servletContext.setContextPath(getContextPath());
        initializeServletContext(initializers);
        initializeFilters();
        initializeDispatcherServlet();
        NettyServerConfiguration configuration = new NettyServerConfiguration(
            getPort(), getAddress(), properties.bossThreads(), properties.workerThreads(),
            properties.keepAlive());
        NettyServer nettyServer = new NettyServer(configuration, channelInitializer, ioHandlerFactory, channelGroup);
        return new NettyWebServer(nettyServer, properties.shutdownGracePeriod());
    }

    private void verifySslNotConfigured() {
        // Because this factory is a ConfigurableServletWebServerFactory, Boot binds server.ssl.* onto it,
        // but the Netty pipeline has no SslHandler yet (issue #16). Fail fast rather than silently serving
        // plaintext while the application looks TLS-configured.
        if (Ssl.isEnabled(getSsl())) {
            throw new WebServerException("server.ssl.* is configured but netty-loom-spring does not support "
                + "TLS yet (see issue #16). Remove server.ssl.* or set server.ssl.enabled=false.", null);
        }
    }

    private void initializeServletContext(ServletContextInitializer... initializers) {
        // Run both the container-supplied initializers and any registered on this factory via the
        // inherited addInitializers/setInitializers (stored in the settings), mirroring the merge that
        // AbstractServletWebServerFactory performs for Tomcat/Jetty.
        List<ServletContextInitializer> merged = new ArrayList<>(List.of(initializers));
        merged.addAll(getSettings().getInitializers());
        for (ServletContextInitializer initializer : merged) {
            try {
                initializer.onStartup(servletContext);
            } catch (ServletException e) {
                throw new WebServerException("Failed to run servlet context initializer", e);
            }
        }
    }

    private void initializeFilters() {
        for (RegisteredFilter registeredFilter : servletContext.getRegisteredFilters()) {
            try {
                registeredFilter.filter().init(new NettyFilterConfig(registeredFilter.name(), servletContext));
            } catch (ServletException e) {
                throw new WebServerException("Failed to initialize filter '" + registeredFilter.name() + "'", e);
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
