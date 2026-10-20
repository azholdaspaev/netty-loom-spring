package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.NettyLoomAutoConfiguration;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.properties.NettyLoomProperties;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyIoHandlerFactory;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerConfiguration;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyForwardedHeaderResolver;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyRequestOriginResolver;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import jakarta.servlet.ServletException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.boot.web.server.servlet.ServletContextInitializers;
import org.springframework.boot.web.server.servlet.ServletWebServerSettings;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NettyServletWebServerFactory extends AbstractConfigurableWebServerFactory
    implements ConfigurableServletWebServerFactory, EnvironmentAware {

    private final ServletWebServerSettings settings = new ServletWebServerSettings();

    private final NettyIoHandlerFactory ioHandlerFactory;
    private final NettyServerChannelInitializer channelInitializer;
    private final HttpConnectionRegistry connectionRegistry;
    private final NettyServletContext servletContext;
    private final DispatcherServlet dispatcherServlet;
    private final NettyLoomProperties properties;

    private boolean useForwardHeaders;
    // Replaced by setEnvironment, which registerSingleton and direct construction never call.
    private ServerPropertiesValidator validator = new ServerPropertiesValidator(new StandardEnvironment());

    /**
     * Qualified for Boot's management child context, which autowires this constructor from a copy of
     * the parent's bean definition: unqualified, the non-default servlet context resolved to the parent's.
     */
    public NettyServletWebServerFactory(NettyIoHandlerFactory ioHandlerFactory,
                                        NettyServerChannelInitializer channelInitializer,
                                        HttpConnectionRegistry connectionRegistry,
                                        @Qualifier(NettyLoomAutoConfiguration.SERVLET_CONTEXT_BEAN) NettyServletContext servletContext,
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
    public void setEnvironment(Environment environment) {
        this.validator = new ServerPropertiesValidator(environment);
    }

    @Override
    public ServletWebServerSettings getSettings() {
        return settings;
    }

    public void setUseForwardHeaders(boolean useForwardHeaders) {
        this.useForwardHeaders = useForwardHeaders;
    }

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        validator.requireSupported(getSsl(), getSettings().getSession());
        validator.warnUnsupported();
        /*
         * Set the context path before the initializer/filter/servlet startup phases so any component
         * that reads ServletContext.getContextPath() during onStartup/init sees the configured value,
         * as the Jakarta contract requires (rather than the default "").
         */
        servletContext.setContextPath(getContextPath());
        servletContext.setServletContextName(getSettings().getDisplayName());
        configureMimeMappings();
        configureSessions();
        configureCookieSameSite();
        configureErrorPages();
        configureRequestOrigin();
        initializeServletContext(initializers);
        // Before filters and servlets; see fireContextInitialized's javadoc for why that order is fixed.
        servletContext.fireContextInitialized();
        initializeFilters();
        initializeDispatcherServlet();
        /*
         * Only now is initialization over; markInitialized's javadoc says what freezes and why it waits
         * for filter and servlet init. Boot's SessionConfiguringInitializer runs earlier either way, and
         * any initializer failure aborts startup before the server is returned.
         */
        servletContext.markInitialized();
        NettyServerConfiguration configuration = new NettyServerConfiguration(
            getPort(), getAddress(), properties.bossThreads(), properties.workerThreads(),
            properties.tcpKeepAlive(), properties.acceptCount(), properties.maxConnections());
        NettyServer nettyServer = new NettyServer(configuration, channelInitializer, ioHandlerFactory, connectionRegistry);
        return new NettyServletWebServer(nettyServer, getShutdown(), properties.shutdownGracePeriod());
    }

    private void configureMimeMappings() {
        Map<String, String> mimeMappings = new HashMap<>();
        for (MimeMappings.Mapping mapping : getSettings().getMimeMappings()) {
            mimeMappings.put(mapping.getExtension(), mapping.getMimeType());
        }
        servletContext.setMimeMappings(mimeMappings);
    }

    /**
     * Boot's {@code SessionConfiguringInitializer} carries neither the timeout nor {@code same-site},
     * which it routes via a Tomcat cookie processor; run first so an initializer can still override.
     */
    private void configureSessions() {
        Session session = getSettings().getSession();
        /*
         * The conversion itself belongs to the manager, which owns the field and the "zero means never
         * expires" rule; the factory only decides which setting feeds it.
         */
        servletContext.getSessionManager().setDefaultMaxInactiveInterval(session.getTimeout());
        Cookie.SameSite sameSite = session.getCookie().getSameSite();
        if (sameSite != null && sameSite.attributeValue() != null) {
            servletContext.getSessionCookieConfig()
                .setAttribute(CookieHeaderNames.SAMESITE, sameSite.attributeValue());
        }
    }

    /**
     * Tomcat additionally prepends a rule for the session cookie; unneeded here (issue #85), since
     * {@link #configureSessions()} writes {@code same-site} as an attribute {@code addCookie} prefers.
     */
    private void configureCookieSameSite() {
        // Diverges from Tomcat at same-site=omitted: docs/compatibility-matrix.md, CookieSameSiteSupplier beans.
        List<? extends CookieSameSiteSupplier> suppliers = getSettings().getCookieSameSiteSuppliers();
        if (!suppliers.isEmpty()) {
            servletContext.setCookieSameSiteResolver(new SuppliedCookieSameSiteResolver(suppliers));
        }
    }

    private void configureErrorPages() {
        servletContext.setErrorPageResolver(new RegisteredErrorPageResolver(getErrorPages()));
    }

    private void configureRequestOrigin() {
        servletContext.setRequestOriginResolver(useForwardHeaders
            ? new NettyForwardedHeaderResolver(properties.forwardedHeaders(), properties.internalProxies())
            : NettyRequestOriginResolver.DIRECT);
    }

    private void initializeServletContext(ServletContextInitializer... initializers) {
        /*
         * Boot's own merge, rather than a hand-rolled one: it prepends the initializers that apply
         * server.servlet.context-parameters and the session cookie configuration, so those properties
         * reach the context by exactly the route they take for Tomcat and Jetty.
         */
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
        try {
            servletContext.initializeFilters();
        } catch (ServletException e) {
            throw new WebServerException("Failed to initialize filters", e);
        }
    }

    private void initializeDispatcherServlet() {
        try {
            String servletName = DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME;
            servletContext.initializeServlet(servletName, dispatcherServlet);
        } catch (ServletException e) {
            throw new WebServerException("Failed to initialize dispatcher servlet", e);
        }
    }
}
