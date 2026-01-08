package io.github.azholdaspaev.nettyloom.autoconfigure;

import io.github.azholdaspaev.nettyloom.autoconfigure.handler.SpringMvcBridgeHandler;
import io.github.azholdaspaev.nettyloom.core.executor.VirtualThreadExecutorFactory;
import io.github.azholdaspaev.nettyloom.core.server.NettyServer;
import io.github.azholdaspaev.nettyloom.core.server.NettyServerConfiguration;
import io.github.azholdaspaev.nettyloom.core.server.NettyWebServer;
import io.github.azholdaspaev.nettyloom.mvc.servlet.NettyServletContext;
import jakarta.servlet.ServletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;

/**
 * Spring Boot {@link org.springframework.boot.web.servlet.server.ServletWebServerFactory}
 * implementation that creates Netty-based web servers with virtual thread support.
 *
 * <p>This factory:
 * <ul>
 *   <li>Creates a {@link NettyServletContext} for servlet/filter registration</li>
 *   <li>Applies all {@link ServletContextInitializer}s (including DispatcherServlet registration)</li>
 *   <li>Creates a {@link SpringMvcBridgeHandler} to bridge Netty to Spring MVC</li>
 *   <li>Returns a {@link NettyWebServer} that manages the server lifecycle</li>
 * </ul>
 *
 * <p>Usage in Spring Boot applications:
 * <pre>
 * // Add to pom.xml or build.gradle
 * implementation("io.github.azholdaspaev:netty-loom-spring-boot-starter")
 *
 * // Exclude Tomcat
 * implementation("org.springframework.boot:spring-boot-starter-web") {
 *     exclude group: "org.springframework.boot", module: "spring-boot-starter-tomcat"
 * }
 * </pre>
 */
public class NettyServletWebServerFactory extends AbstractServletWebServerFactory {

    private static final Logger logger = LoggerFactory.getLogger(NettyServletWebServerFactory.class);

    private final NettyServerProperties nettyProperties;

    /**
     * Creates a factory with the given Netty-specific properties.
     *
     * @param nettyProperties the Netty server configuration properties
     */
    public NettyServletWebServerFactory(NettyServerProperties nettyProperties) {
        this.nettyProperties = nettyProperties != null ? nettyProperties : new NettyServerProperties();
    }

    /**
     * Creates a factory with default properties.
     */
    public NettyServletWebServerFactory() {
        this(new NettyServerProperties());
    }

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        logger.info("Creating Netty web server with virtual thread support");

        // 1. Create servlet context
        String contextPath = getContextPath() != null ? getContextPath() : "";
        NettyServletContext servletContext = new NettyServletContext(contextPath);

        // 2. Apply all initializers (registers DispatcherServlet, filters, etc.)
        applyInitializers(servletContext, initializers);

        // 3. Initialize all registered servlets and filters
        initializeServletsAndFilters(servletContext);

        // 4. Create virtual thread executor
        ExecutorService virtualThreadExecutor = VirtualThreadExecutorFactory.create("netty-mvc-");

        // 5. Create bridge handler
        SpringMvcBridgeHandler bridgeHandler = new SpringMvcBridgeHandler(
                servletContext,
                virtualThreadExecutor,
                contextPath
        );

        // 6. Build server configuration
        NettyServerConfiguration config = buildConfiguration();

        // 7. Create Netty server with bridge handler
        NettyServer nettyServer = new NettyServer(config, bridgeHandler);

        // 8. Return WebServer wrapper
        return new NettyWebServer(nettyServer, nettyProperties.getShutdownTimeout());
    }

    /**
     * Applies all servlet context initializers.
     */
    private void applyInitializers(NettyServletContext servletContext,
                                    ServletContextInitializer[] initializers) {
        // Merge factory-level initializers with method-level ones
        ServletContextInitializer[] merged = mergeInitializers(initializers);

        for (ServletContextInitializer initializer : merged) {
            try {
                logger.debug("Applying initializer: {}", initializer.getClass().getName());
                initializer.onStartup(servletContext);
            } catch (ServletException e) {
                throw new WebServerException("Failed to apply ServletContextInitializer: " +
                        initializer.getClass().getName(), e);
            }
        }
        logger.debug("Successfully applied {} servlet context initializers", merged.length);
    }

    /**
     * Initializes all registered servlets and filters.
     * This must be called after all initializers have registered their servlets/filters.
     */
    private void initializeServletsAndFilters(NettyServletContext servletContext) {
        try {
            logger.debug("Initializing servlets and filters");
            servletContext.initializeServletsAndFilters();
            logger.debug("Servlets and filters initialized successfully");
        } catch (ServletException e) {
            throw new WebServerException("Failed to initialize servlets and filters", e);
        }
    }

    /**
     * Builds the Netty server configuration from factory and property settings.
     */
    private NettyServerConfiguration buildConfiguration() {
        String host = getHost();
        int port = getPort();

        return NettyServerConfiguration.builder()
                .host(host != null ? host : "0.0.0.0")
                .port(port)
                .bossThreads(nettyProperties.getBossThreads())
                .workerThreads(nettyProperties.getWorkerThreads())
                .maxContentLength(nettyProperties.getMaxContentLength())
                .build();
    }

    /**
     * Gets the host address to bind to.
     */
    private String getHost() {
        InetAddress address = getAddress();
        if (address != null) {
            return address.getHostAddress();
        }
        return null;
    }

    /**
     * Returns the Netty-specific properties.
     *
     * @return the properties
     */
    public NettyServerProperties getNettyProperties() {
        return nettyProperties;
    }
}
