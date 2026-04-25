package io.github.azholdaspaev.nettyloomspring.autoconfigure;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.properties.NettyLoomProperties;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.NettyWebServerFactory;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpExceptionHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestHandler;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.DefaultNettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NamedChannelHandler;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerConfiguration;
import io.github.azholdaspaev.nettyloomspring.mvc.handler.SpringHttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.DefaultNettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    public NettyPipelineConfigurer nettyPipelineConfigurer(HttpRequestDispatcher httpRequestDispatcher,
                                                           ExecutorService nettyLoomDispatchExecutor) {
        return new DefaultNettyPipelineConfigurer(List.of(
            new NamedChannelHandler("httpCodec", new HttpServerCodec(10000, 10000, 10000)),
            new NamedChannelHandler("aggregator", new HttpObjectAggregator(MAX_HTTP_REQUEST_BODY_BYTES)),
            new NamedChannelHandler("dispatcher", new HttpRequestHandler(httpRequestDispatcher, nettyLoomDispatchExecutor)),
            new NamedChannelHandler("exceptionHandler", new HttpExceptionHandler())
        ));
    }

    @Bean
    public ExecutorService nettyLoomDispatchExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public HttpRequestDispatcher httpRequestDispatcher(DispatcherServlet dispatcherServlet) {
        return new SpringHttpRequestDispatcher(dispatcherServlet);
    }
}
