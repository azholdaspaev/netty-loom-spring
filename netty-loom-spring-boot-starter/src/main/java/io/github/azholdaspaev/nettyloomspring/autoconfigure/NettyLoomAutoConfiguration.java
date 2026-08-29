package io.github.azholdaspaev.nettyloomspring.autoconfigure;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.properties.NettyLoomProperties;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.NettyWebServerFactory;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.SessionStoreLifecycle;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpDecoderFailureHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpDrainHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpExceptionHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpPipeliningHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpReadTimeoutHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestBodyLimitHandler;
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
            // Directly below the codec so a connection counts as busy from the head of a request, before
            // its body has finished arriving; outbound of httpKeepAlive so it can stamp
            // Connection: close before that handler decides whether to close.
            new NamedChannelHandler("drain", () -> new HttpDrainHandler(httpConnectionRegistry)),
            // Above the pipelining gate so its count stays a property of what the client has delivered
            // rather than of what that handler has released; correctness holds on either side.
            new NamedChannelHandler("readTimeout", () -> new HttpReadTimeoutHandler(readTimeoutNanos, TimeUnit.NANOSECONDS)),
            // Above the dispatcher so requests are gated before dispatch while responses still pass back
            // through, and above bodyLimit so that handler's 100 Continue and 413 are sequenced rather
            // than travelling towards the head unsequenced (issue #78).
            new NamedChannelHandler("pipelining", HttpPipeliningHandler::new),
            // Below the gate so the rejection is sequenced behind an earlier pipelined response and releases
            // the gate on its way out. Nothing is lost by rejecting this late: the decoder discards every
            // byte after a bad message, so no request can be queued behind one.
            NamedChannelHandler.shared("decoderFailure", new HttpDecoderFailureHandler()),
            // Below decoderFailure so it counts only what decoded, and above the dispatcher so a body it
            // refuses never reaches one.
            new NamedChannelHandler("bodyLimit", () -> new HttpRequestBodyLimitHandler(MAX_HTTP_REQUEST_BODY_BYTES)),
            new NamedChannelHandler("dispatcher", () -> new HttpRequestHandler(httpRequestDispatcher, nettyLoomDispatchExecutor,
                httpConnectionRegistry, properties.writeStallTimeout())),
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
