package io.github.azholdaspaev.nettyloomspring.autoconfigure;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.properties.NettyLoomProperties;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.NettyWebServerFactory;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpExceptionHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestHandler;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.DefaultNettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NamedChannelHandler;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyIoHandlerFactory;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer;
import io.github.azholdaspaev.nettyloomspring.mvc.handler.SpringHttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.DefaultNettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.servlet.ServletWebServerConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@AutoConfiguration(before = WebMvcAutoConfiguration.class)
@EnableConfigurationProperties(NettyLoomProperties.class)
@Import(ServletWebServerConfiguration.class)
public class NettyLoomAutoConfiguration {

    private static final int MAX_HTTP_REQUEST_BODY_BYTES = 1024 * 1024;
    private static final int MAX_HTTP_INITIAL_LINE_LENGTH = 10_000;
    private static final int MAX_HTTP_HEADER_SIZE = 10_000;
    private static final int MAX_HTTP_CHUNK_SIZE = 10_000;

    @Bean
    public NettyWebServerFactory nettyWebServerFactory(NettyIoHandlerFactory nettyIoHandlerFactory,
                                                       NettyServerChannelInitializer nettyServerChannelInitializer,
                                                       ChannelGroup nettyLoomChannelGroup,
                                                       NettyServletContext servletContext,
                                                       DispatcherServlet dispatcherServlet,
                                                       NettyLoomProperties properties) {
        return new NettyWebServerFactory(nettyIoHandlerFactory, nettyServerChannelInitializer,
            nettyLoomChannelGroup, servletContext, dispatcherServlet, properties);
    }

    @Bean
    public NettyIoHandlerFactory nettyIoHandlerFactory(NettyLoomProperties properties) {
        return new NettyIoHandlerFactory(properties.transport());
    }

    @Bean
    public NettyServletContext nettyServletContext(ApplicationContext applicationContext) {
        // Resolve context resources against the application's loader, not this library's: under
        // devtools the application lives in a RestartClassLoader that the library never sees.
        return new DefaultNettyServletContext(applicationContext.getClassLoader());
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
    public NettyPipelineConfigurer nettyPipelineConfigurer(NettyLoomProperties properties,
                                                           HttpRequestDispatcher httpRequestDispatcher,
                                                           ExecutorService nettyLoomDispatchExecutor) {
        long readTimeoutMillis = properties.readTimeout().toMillis();
        return new DefaultNettyPipelineConfigurer(List.of(
            new NamedChannelHandler("readTimeout", () -> new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS)),
            new NamedChannelHandler("httpCodec", () -> new HttpServerCodec(MAX_HTTP_INITIAL_LINE_LENGTH, MAX_HTTP_HEADER_SIZE, MAX_HTTP_CHUNK_SIZE)),
            new NamedChannelHandler("httpKeepAlive", HttpServerKeepAliveHandler::new),
            new NamedChannelHandler("aggregator", () -> new HttpObjectAggregator(MAX_HTTP_REQUEST_BODY_BYTES)),
            new NamedChannelHandler("dispatcher", () -> new HttpRequestHandler(httpRequestDispatcher, nettyLoomDispatchExecutor)),
            NamedChannelHandler.shared("exceptionHandler", new HttpExceptionHandler())
        ));
    }

    @Bean
    public ExecutorService nettyLoomDispatchExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public HttpRequestDispatcher httpRequestDispatcher(DispatcherServlet dispatcherServlet,
                                                       NettyServletContext servletContext) {
        return new SpringHttpRequestDispatcher(dispatcherServlet, servletContext);
    }
}
