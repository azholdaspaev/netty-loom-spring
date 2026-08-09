package io.github.azholdaspaev.nettyloomspring.autoconfigure;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.properties.NettyLoomProperties;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.NettyWebServerFactory;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.SessionStoreLifecycle;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpDrainHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpExceptionHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpPipeliningHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpReadTimeoutHandler;
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
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.servlet.ServletWebServerConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
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
                                                       HttpConnectionRegistry httpConnectionRegistry,
                                                       NettyServletContext servletContext,
                                                       DispatcherServlet dispatcherServlet,
                                                       NettyLoomProperties properties) {
        return new NettyWebServerFactory(nettyIoHandlerFactory, nettyServerChannelInitializer,
            httpConnectionRegistry, servletContext, dispatcherServlet, properties);
    }

    @Bean
    public NettyIoHandlerFactory nettyIoHandlerFactory(NettyLoomProperties properties) {
        return new NettyIoHandlerFactory(properties.transport());
    }

    @Bean
    public NettyServletContext nettyServletContext() {
        return new DefaultNettyServletContext();
    }

    @Bean
    public SessionStoreLifecycle sessionStoreLifecycle(NettyServletContext servletContext) {
        return new SessionStoreLifecycle(servletContext);
    }

    @Bean
    public HttpConnectionRegistry httpConnectionRegistry() {
        return new HttpConnectionRegistry(new DefaultChannelGroup("netty-loom-channels", GlobalEventExecutor.INSTANCE));
    }

    @Bean
    public NettyServerChannelInitializer nettyServerChannelInitializer(NettyPipelineConfigurer nettyPipelineConfigurer,
                                                                       HttpConnectionRegistry httpConnectionRegistry) {
        return new NettyServerChannelInitializer(nettyPipelineConfigurer, httpConnectionRegistry);
    }

    @Bean
    public NettyPipelineConfigurer nettyPipelineConfigurer(NettyLoomProperties properties,
                                                           HttpRequestDispatcher httpRequestDispatcher,
                                                           ExecutorService nettyLoomDispatchExecutor,
                                                           HttpConnectionRegistry httpConnectionRegistry) {
        // Nanoseconds, not millis: toMillis() truncates, so a sub-millisecond read-timeout would arrive as
        // zero -- which the handler treats as "disabled", silently turning the slow-loris guard off.
        long readTimeoutNanos = properties.readTimeout().toNanos();
        return new DefaultNettyPipelineConfigurer(List.of(
            new NamedChannelHandler("httpCodec", () -> new HttpServerCodec(MAX_HTTP_INITIAL_LINE_LENGTH, MAX_HTTP_HEADER_SIZE, MAX_HTTP_CHUNK_SIZE)),
            new NamedChannelHandler("httpKeepAlive", HttpServerKeepAliveHandler::new),
            // Above the aggregator so a connection counts as busy from the head of a request, before
            // its body has finished arriving; outbound of httpKeepAlive so it can stamp
            // Connection: close before that handler decides whether to close.
            new NamedChannelHandler("drain", () -> new HttpDrainHandler(httpConnectionRegistry)),
            new NamedChannelHandler("aggregator", () -> new HttpObjectAggregator(MAX_HTTP_REQUEST_BODY_BYTES)),
            // Below the aggregator so the timeout measures whole requests rather than bytes -- at the head
            // it saw none of the exchange and so counted dispatch time, closing a slow handler's connection
            // mid-request. Above the pipelining gate so its count stays a property of what the client has
            // delivered rather than of what that handler has released; correctness holds on either side.
            new NamedChannelHandler("readTimeout", () -> new HttpReadTimeoutHandler(readTimeoutNanos, TimeUnit.NANOSECONDS)),
            // Below the aggregator so it gates whole requests, and so the aggregator's 100 Continue --
            // written from that handler's own context, towards the head -- never reaches it; above the
            // dispatcher so requests are gated before dispatch while responses still pass back through.
            new NamedChannelHandler("pipelining", HttpPipeliningHandler::new),
            new NamedChannelHandler("dispatcher", () -> new HttpRequestHandler(httpRequestDispatcher, nettyLoomDispatchExecutor, httpConnectionRegistry)),
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
