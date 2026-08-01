package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.properties.NettyLoomProperties;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyIoHandlerFactory;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerConfiguration;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyFilterConfig;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletConfig;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.RegisteredFilter;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import jakarta.servlet.ServletException;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.servlet.ServletContextInitializers;
import org.springframework.boot.web.server.servlet.ServletWebServerSettings;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.web.servlet.DispatcherServlet;

public class NettyWebServerFactory extends AbstractConfigurableWebServerFactory
    implements ConfigurableServletWebServerFactory {

    private final ServletWebServerSettings settings = new ServletWebServerSettings();

    private final NettyIoHandlerFactory ioHandlerFactory;
    private final NettyServerChannelInitializer channelInitializer;
    private final HttpConnectionRegistry connectionRegistry;
    private final NettyServletContext servletContext;
    private final DispatcherServlet dispatcherServlet;
    private final NettyLoomProperties properties;

    public NettyWebServerFactory(NettyIoHandlerFactory ioHandlerFactory,
                                 NettyServerChannelInitializer channelInitializer,
                                 HttpConnectionRegistry connectionRegistry,
                                 NettyServletContext servletContext,
                                 DispatcherServlet dispatcherServlet,
                                 NettyLoomProperties properties) {
        this.ioHandlerFactory = ioHandlerFactory;
        this.channelInitializer = channelInitializer;
        this.connectionRegistry = connectionRegistry;
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
        configureSessions();
        initializeServletContext(initializers);
        // Before filters and servlets; see fireContextInitialized's javadoc for why that order is fixed.
        servletContext.fireContextInitialized();
        initializeFilters();
        initializeDispatcherServlet();
        // Only now is initialization over, so the session configuration freezes: every
        // SessionCookieConfig and ServletContext session setter is specified to throw from here on.
        // Deliberately after filter and servlet init rather than after the initializers -- Tomcat is
        // still in STARTING_PREP during those, so a Filter.init that configures the session cookie
        // starts there and must start here. Boot's SessionConfiguringInitializer runs earlier either
        // way, and any initializer failure aborts startup before the server is returned.
        servletContext.getSessionManager().markContextInitialized();
        // Listener registration closes on the same beat, for the reason markInitialized's javadoc gives.
        servletContext.getListenerRegistry().markInitialized();
        NettyServerConfiguration configuration = new NettyServerConfiguration(
            getPort(), getAddress(), properties.bossThreads(), properties.workerThreads(),
            properties.tcpKeepAlive());
        NettyServer nettyServer = new NettyServer(configuration, channelInitializer, ioHandlerFactory, connectionRegistry);
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

    /**
     * Applies the two session settings Boot's own {@code SessionConfiguringInitializer} does not carry.
     * The cookie properties arrive with that initializer during
     * {@link #initializeServletContext(ServletContextInitializer...)}; the timeout it never touches, and
     * {@code same-site} it applies through a container-specific path (a Tomcat cookie processor) rather
     * than the {@code ServletContext}. Both are set before the initializers run so an application
     * initializer can still override them.
     */
    private void configureSessions() {
        Session session = getSettings().getSession();
        verifySessionPersistenceNotConfigured(session);
        // The conversion itself belongs to the manager, which owns the field and the "zero means never
        // expires" rule; the factory only decides which setting feeds it.
        servletContext.getSessionManager().setDefaultMaxInactiveInterval(session.getTimeout());
        Cookie.SameSite sameSite = session.getCookie().getSameSite();
        if (sameSite != null && sameSite.attributeValue() != null) {
            servletContext.getSessionCookieConfig()
                .setAttribute(CookieHeaderNames.SAMESITE, sameSite.attributeValue());
        }
    }

    private void verifySessionPersistenceNotConfigured(Session session) {
        // Under Tomcat this writes SESSIONS.ser and survives a restart. This container has an in-memory
        // store only (issue #13 non-goal), so honouring the property is impossible and ignoring it would
        // silently lose every session on deploy -- the same fail-fast contract as server.ssl.*.
        if (session.isPersistent()) {
            throw new WebServerException("server.servlet.session.persistent=true is configured but "
                + "netty-loom-spring stores sessions in memory only and cannot persist them across "
                + "restarts. Remove the property, or use Spring Session for a durable store.", null);
        }
    }

    private void initializeServletContext(ServletContextInitializer... initializers) {
        // Boot's own merge, rather than a hand-rolled one: it prepends the initializers that apply
        // server.servlet.context-parameters and the session cookie configuration, so those properties
        // reach the context by exactly the route they take for Tomcat and Jetty.
        for (ServletContextInitializer initializer : ServletContextInitializers.from(getSettings(), initializers)) {
            runInitializer(initializer);
        }
    }

    private void runInitializer(ServletContextInitializer initializer) {
        try {
            initializer.onStartup(servletContext);
        } catch (ServletException e) {
            throw new WebServerException("Failed to run servlet context initializer", e);
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
