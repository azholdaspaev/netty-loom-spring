package io.azholdaspaev.nettyloom.autoconfigure;

import io.azholdaspaev.nettyloom.autoconfigure.server.NettyWebServerFactory;
import io.azholdaspaev.nettyloom.core.handler.HttpRequestDispatcher;
import io.azholdaspaev.nettyloom.core.handler.HttpRequestHandler;
import io.azholdaspaev.nettyloom.core.pipeline.DefaultNettyPipelineConfigurer;
import io.azholdaspaev.nettyloom.core.pipeline.NamedChannelHandler;
import io.azholdaspaev.nettyloom.core.pipeline.NettyPipelineConfigurer;
import io.azholdaspaev.nettyloom.core.server.NettyServer;
import io.azholdaspaev.nettyloom.core.server.NettyServerChannelInitializer;
import io.azholdaspaev.nettyloom.core.server.NettyServerConfiguration;
import io.azholdaspaev.nettyloom.mvc.handler.SpringHttpRequestDispatcher;
import io.azholdaspaev.nettyloom.mvc.servlet.DefaultNettyServletContext;
import io.azholdaspaev.nettyloom.mvc.servlet.NettyServletContext;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

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
    public NettyPipelineConfigurer nettyPipelineConfigurer(HttpRequestDispatcher httpRequestDispatcher) {
        return new DefaultNettyPipelineConfigurer(List.of(
            new NamedChannelHandler("httpCodec", new HttpServerCodec(10000, 10000, 10000)),
            new NamedChannelHandler("aggregator", new HttpObjectAggregator(10000)),
            new NamedChannelHandler("dispatcher", new HttpRequestHandler(httpRequestDispatcher))
        ));
    }

    @Bean
    public HttpRequestDispatcher httpRequestDispatcher(DispatcherServlet dispatcherServlet) {
        return new SpringHttpRequestDispatcher(dispatcherServlet);
    }
}
