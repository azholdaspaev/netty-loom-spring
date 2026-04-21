package io.azholdaspaev.nettyloom.autoconfigure;

import io.azholdaspaev.nettyloom.autoconfigure.properties.NettyLoomProperties;
import io.azholdaspaev.nettyloom.autoconfigure.server.NettyWebServerFactory;
import io.azholdaspaev.nettyloom.core.handler.HttpExceptionHandler;
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
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.List;

@AutoConfiguration(before = WebMvcAutoConfiguration.class)
@EnableConfigurationProperties(NettyLoomProperties.class)
public class NettyLoomAutoConfiguration {

    private static final int MAX_HTTP_REQUEST_BODY_BYTES = 1024 * 1024;

    @Bean
    public NettyWebServerFactory nettyWebServerFactory(NettyServer nettyServer,
                                                       NettyServletContext servletContext,
                                                       DispatcherServlet dispatcherServlet,
                                                       NettyLoomProperties properties) {
        return new NettyWebServerFactory(nettyServer, servletContext, dispatcherServlet,
            properties.shutdownGracePeriod());
    }

    @Bean
    public NettyServer nettyServer(NettyLoomProperties properties,
                                   NettyServerChannelInitializer nettyServerChannelInitializer,
                                   ChannelGroup nettyLoomChannelGroup) {
        NettyServerConfiguration configuration = new NettyServerConfiguration(
            properties.port(), properties.bossThreads(), properties.workerThreads(), properties.keepAlive()
        );
        return new NettyServer(configuration, nettyServerChannelInitializer, nettyLoomChannelGroup);
    }

    @Bean
    public NettyServletContext nettyServletContext() {
        return new DefaultNettyServletContext();
    }

    @Bean
    public ChannelGroup nettyLoomChannelGroup() {
        return new DefaultChannelGroup("netty-loom-channels", GlobalEventExecutor.INSTANCE);
    }

    @Bean
    public NettyServerChannelInitializer nettyServerChannelInitializer(NettyPipelineConfigurer nettyPipelineConfigurer,
                                                                       ChannelGroup nettyLoomChannelGroup) {
        return new NettyServerChannelInitializer(nettyPipelineConfigurer, nettyLoomChannelGroup);
    }

    @Bean
    public NettyPipelineConfigurer nettyPipelineConfigurer(HttpRequestDispatcher httpRequestDispatcher) {
        return new DefaultNettyPipelineConfigurer(List.of(
            new NamedChannelHandler("httpCodec", new HttpServerCodec(10000, 10000, 10000)),
            new NamedChannelHandler("aggregator", new HttpObjectAggregator(MAX_HTTP_REQUEST_BODY_BYTES)),
            new NamedChannelHandler("dispatcher", new HttpRequestHandler(httpRequestDispatcher)),
            new NamedChannelHandler("exceptionHandler", new HttpExceptionHandler())
        ));
    }

    @Bean
    public HttpRequestDispatcher httpRequestDispatcher(DispatcherServlet dispatcherServlet) {
        return new SpringHttpRequestDispatcher(dispatcherServlet);
    }
}
