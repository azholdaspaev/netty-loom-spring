package io.azholdaspaev.nettyloom.autoconfigure;

import io.azholdaspaev.nettyloom.autoconfigure.server.NettyWebServerFactory;
import io.azholdaspaev.nettyloom.core.pipeline.DefaultNettyPipelineConfigurer;
import io.azholdaspaev.nettyloom.core.pipeline.NettyPipelineConfigurer;
import io.azholdaspaev.nettyloom.core.server.NettyServer;
import io.azholdaspaev.nettyloom.core.server.NettyServerChannelInitializer;
import io.azholdaspaev.nettyloom.core.server.NettyServerConfiguration;
import io.azholdaspaev.nettyloom.mvc.servlet.DefaultNettyServletContext;
import io.azholdaspaev.nettyloom.mvc.servlet.NettyServletContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class NettyLoomAutoConfiguration {

    @Bean
    public NettyWebServerFactory nettyWebServerFactory(NettyServer nettyServer, NettyServletContext servletContext) {
        return new NettyWebServerFactory(nettyServer, servletContext);
    }

    @Bean
    public NettyServer nettyServer(NettyServerChannelInitializer nettyServerChannelInitializer) {
        return new NettyServer(new NettyServerConfiguration(0), nettyServerChannelInitializer);
    }

    @Bean
    public NettyServletContext nettyServletContext() {
        return new DefaultNettyServletContext();
    }

    @Bean
    public NettyServerChannelInitializer nettyServerChannelInitializer(NettyPipelineConfigurer nettyPipelineConfigurer) {
        return new NettyServerChannelInitializer(nettyPipelineConfigurer);
    }

    @Bean
    public NettyPipelineConfigurer nettyPipelineConfigurer() {
        return new DefaultNettyPipelineConfigurer(List.of());
    }
}
