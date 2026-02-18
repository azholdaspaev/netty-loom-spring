package io.github.azholdaspaev.nettyloom.autoconfigure;

import io.github.azholdaspaev.nettyloom.autoconfigure.server.NettyWebServerFactory;
import io.github.azholdaspaev.nettyloom.core.server.NettyServer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = ServletWebServerFactoryAutoConfiguration.class)
@ConditionalOnClass(NettyServer.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
// @EnableConfigurationProperties(NettyLoomProperties.class)
public class NettyLoomAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WebServerFactory.class)
    public NettyWebServerFactory nettyServletWebServerFactory(ServerProperties serverProperties) {

        NettyWebServerFactory factory = new NettyWebServerFactory();

        // --- Standard Spring Boot server properties ---
        factory.setPort(serverProperties.getPort() != null ? serverProperties.getPort() : 8080);

        if (serverProperties.getAddress() != null) {
            factory.setAddress(serverProperties.getAddress());
        }

        //        // --- Netty-Loom specific properties ---
        //        factory.setBossThreads(nettyLoomProperties.getBossThreads());
        //        factory.setWorkerThreads(nettyLoomProperties.getWorkerThreads());
        //        factory.setMaxContentLength(nettyLoomProperties.getMaxContentLength());
        //        factory.setMaxHeaderSize(nettyLoomProperties.getMaxHeaderSize());
        //        factory.setIdleTimeout(nettyLoomProperties.getIdleTimeout());
        //        factory.setKeepAlive(nettyLoomProperties.isKeepAlive());
        //        factory.setTcpNoDelay(nettyLoomProperties.isTcpNoDelay());
        //
        //        // --- Virtual threads ---
        //        if (nettyLoomProperties.getVirtualThreads().isEnabled()) {
        //            factory.setVirtualThreadsEnabled(true);
        //            int maxInFlight = nettyLoomProperties.getVirtualThreads()
        //                    .getMaxInFlightRequests();
        //            if (maxInFlight > 0) {
        //                factory.setMaxInFlightRequests(maxInFlight);
        //            }
        //            // 0 means auto: Runtime.getRuntime().availableProcessors() * 256
        //        }
        //
        //        // --- Transport ---
        //        String transport = nettyLoomProperties.getTransport();
        //        if (!"auto".equalsIgnoreCase(transport)) {
        //            factory.setTransportType(TransportType.valueOf(transport.toUpperCase()));
        //        }
        //
        //        // --- Metrics ---
        //        factory.setServerMetrics(
        //                metricsProvider.getIfAvailable(NoOpServerMetrics::new));

        return factory;
    }
}
