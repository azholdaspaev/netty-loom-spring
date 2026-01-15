package io.github.azholdaspaev.nettyloom.autoconfigure;

import io.github.azholdaspaev.nettyloom.core.executor.ExecutorFactory;
import io.github.azholdaspaev.nettyloom.core.executor.VirtualThreadExecutorFactory;
import io.github.azholdaspaev.nettyloom.core.server.NettyServer;
import jakarta.servlet.Servlet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Netty-based servlet web server.
 *
 * <p>This configuration is activated when:
 * <ul>
 *   <li>{@link NettyServer} and {@link Servlet} classes are on the classpath</li>
 *   <li>The application is a servlet-based web application</li>
 *   <li>No other {@link ServletWebServerFactory} bean is present</li>
 * </ul>
 *
 * <p>The configuration backs off when Tomcat, Jetty, or Undertow is present,
 * as they provide their own {@link ServletWebServerFactory} beans.
 */
@AutoConfiguration(before = ServletWebServerFactoryAutoConfiguration.class)
@ConditionalOnClass({NettyServer.class, Servlet.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(NettyServerProperties.class)
public class NettyServerAutoConfiguration {

    /**
     * Creates the Netty servlet web server factory bean.
     *
     * <p>This bean is only created if no other {@link ServletWebServerFactory}
     * is already defined. This allows users to provide their own factory or
     * use a different embedded server (Tomcat, Jetty, etc.).
     *
     * @param nettyProperties the Netty server configuration properties
     * @param executorFactoryProvider optional custom executor factory
     * @param customizers factory customizers to apply
     * @return the configured Netty servlet web server factory
     */
    @Bean
    @ConditionalOnMissingBean(ServletWebServerFactory.class)
    public NettyServletWebServerFactory nettyServletWebServerFactory(
            NettyServerProperties nettyProperties,
            ObjectProvider<ExecutorFactory> executorFactoryProvider,
            ObjectProvider<WebServerFactoryCustomizer<NettyServletWebServerFactory>> customizers) {

        ExecutorFactory executorFactory = executorFactoryProvider
                .getIfAvailable(() -> new VirtualThreadExecutorFactory("netty-mvc-"));

        NettyServletWebServerFactory factory = new NettyServletWebServerFactory(nettyProperties, executorFactory);
        customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }
}
