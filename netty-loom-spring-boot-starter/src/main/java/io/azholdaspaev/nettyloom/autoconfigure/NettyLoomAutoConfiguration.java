package io.azholdaspaev.nettyloom.autoconfigure;

import io.azholdaspaev.nettyloom.autoconfigure.server.NettyWebServerFactory;
import io.azholdaspaev.nettyloom.core.server.NettyServer;
import io.azholdaspaev.nettyloom.core.server.NettyServerConfiguration;
import io.azholdaspaev.nettyloom.mvc.servlet.DefaultNettyServletContext;
import io.azholdaspaev.nettyloom.mvc.servlet.NettyServletContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class NettyLoomAutoConfiguration {

    @Bean
    public NettyWebServerFactory nettyWebServerFactory(NettyServer nettyServer, NettyServletContext servletContext) {
        return new NettyWebServerFactory(nettyServer, servletContext);
    }

    @Bean
    public NettyServer nettyServer() {
        return new NettyServer(new NettyServerConfiguration(0));
    }

    @Bean
    public NettyServletContext nettyServletContext() {
        return new DefaultNettyServletContext();
    }
}
