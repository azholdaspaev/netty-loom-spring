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
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineStep;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineDefinition;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyIoHandlerFactory;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer;
import io.github.azholdaspaev.nettyloomspring.core.time.Durations;
import io.github.azholdaspaev.nettyloomspring.mvc.handler.SpringHttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.DefaultNettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.servlet.ServletWebServerConfiguration;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Ordered after Boot's own containers, not left to the default:
 * {@code AutoConfigurationSorter.getInPriorityOrder} sorts unordered auto-configurations by class
 * name, so {@code io.github} would register ahead of {@code org.springframework} and the Boot
 * container's {@code @ConditionalOnMissingBean} would be the one to yield. Ordered this way, Netty
 * serves only when it is the sole factory.
 */
@AutoConfiguration(
    before = WebMvcAutoConfiguration.class,
    afterName = {
        "org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration",
        "org.springframework.boot.jetty.autoconfigure.servlet.JettyServletWebServerAutoConfiguration",
        "org.springframework.boot.undertow.autoconfigure.servlet.UndertowServletWebServerAutoConfiguration"
    }
)
@ConditionalOnClass(HttpServerCodec.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties(NettyLoomProperties.class)
@Import(ServletWebServerConfiguration.class)
public class NettyLoomAutoConfiguration {

    public static final String DISPATCH_EXECUTOR_BEAN = "nettyLoomDispatchExecutor";

    @Bean
    @ConditionalOnMissingBean(value = ServletWebServerFactory.class, search = SearchStrategy.CURRENT)
    public NettyWebServerFactory nettyWebServerFactory(NettyIoHandlerFactory nettyIoHandlerFactory,
                                                       NettyServerChannelInitializer nettyServerChannelInitializer,
                                                       HttpConnectionRegistry httpConnectionRegistry,
                                                       NettyServletContext nettyServletContext,
                                                       DispatcherServlet dispatcherServlet,
                                                       NettyLoomProperties properties) {
        return new NettyWebServerFactory(nettyIoHandlerFactory, nettyServerChannelInitializer,
            httpConnectionRegistry, nettyServletContext, dispatcherServlet, properties);
    }

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public NettyIoHandlerFactory nettyIoHandlerFactory(NettyLoomProperties properties) {
        return new NettyIoHandlerFactory(properties.transport());
    }

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public NettyServletContext nettyServletContext() {
        return new DefaultNettyServletContext();
    }

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public SessionStoreLifecycle sessionStoreLifecycle(NettyServletContext nettyServletContext) {
        return new SessionStoreLifecycle(nettyServletContext);
    }

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public HttpConnectionRegistry httpConnectionRegistry() {
        return new HttpConnectionRegistry(new DefaultChannelGroup("netty-loom-channels", GlobalEventExecutor.INSTANCE));
    }

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public NettyServerChannelInitializer nettyServerChannelInitializer(NettyPipelineDefinition nettyPipelineDefinition,
                                                                       HttpConnectionRegistry httpConnectionRegistry) {
        return new NettyServerChannelInitializer(nettyPipelineDefinition, httpConnectionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public NettyPipelineDefinition nettyPipelineDefinition(NettyLoomProperties properties,
                                                           HttpRequestDispatcher httpRequestDispatcher,
                                                           @Qualifier(DISPATCH_EXECUTOR_BEAN) ExecutorService nettyLoomDispatchExecutor,
                                                           HttpConnectionRegistry httpConnectionRegistry) {
        /*
         * Nanoseconds, not millis: toMillis() truncates, so a sub-millisecond read-timeout would arrive as
         * zero -- which the handler treats as "disabled", silently turning the slow-loris guard off.
         */
        long readTimeoutNanos = Durations.toNanosSaturated(properties.readTimeout());
        int maxInitialLineLength = (int) properties.maxInitialLineLength().toBytes();
        int maxHeaderSize = (int) properties.maxHeaderSize().toBytes();
        int maxChunkSize = (int) properties.maxChunkSize().toBytes();
        long maxHttpBodyBytes = properties.maxHttpBodySize().toBytes();
        return new NettyPipelineDefinition(List.of(
            new NettyPipelineStep("httpCodec", () -> new HttpServerCodec(maxInitialLineLength, maxHeaderSize, maxChunkSize)),
            new NettyPipelineStep("httpKeepAlive", HttpServerKeepAliveHandler::new),
            /*
             * Directly below the codec so a connection counts as busy from the head of a request, before
             * its body has finished arriving; outbound of httpKeepAlive so it can stamp
             * Connection: close before that handler decides whether to close.
             */
            new NettyPipelineStep("drain", () -> new HttpDrainHandler(httpConnectionRegistry)),
            /*
             * Above the pipelining gate so its count stays a property of what the client has delivered
             * rather than of what that handler has released; correctness holds on either side.
             */
            new NettyPipelineStep("readTimeout", () -> new HttpReadTimeoutHandler(readTimeoutNanos, TimeUnit.NANOSECONDS)),
            /*
             * Above the dispatcher so requests are gated before dispatch while responses still pass back
             * through, and above bodyLimit so that handler's 100 Continue and 413 are sequenced rather
             * than travelling towards the head unsequenced (issue #78).
             */
            new NettyPipelineStep("pipelining", HttpPipeliningHandler::new),
            /*
             * Below the gate so the rejection is sequenced behind an earlier pipelined response and releases
             * the gate on its way out. Nothing is lost by rejecting this late: the decoder discards every
             * byte after a bad message, so no request can be queued behind one.
             */
            NettyPipelineStep.shared("decoderFailure", new HttpDecoderFailureHandler()),
            /*
             * Below decoderFailure so it counts only what decoded, and above the dispatcher so a body it
             * refuses never reaches one.
             */
            new NettyPipelineStep("bodyLimit", () -> new HttpRequestBodyLimitHandler(maxHttpBodyBytes)),
            new NettyPipelineStep("dispatcher", () -> new HttpRequestHandler(httpRequestDispatcher, nettyLoomDispatchExecutor,
                httpConnectionRegistry, properties.writeStallTimeout())),
            NettyPipelineStep.shared("exceptionHandler", new HttpExceptionHandler())
        ));
    }

    /**
     * Guarded by name, not by type: an application's own {@code ExecutorService} bean would otherwise
     * displace this one, and every request would then dispatch onto that pool's threads.
     */
    @Bean(DISPATCH_EXECUTOR_BEAN)
    @ConditionalOnMissingBean(name = DISPATCH_EXECUTOR_BEAN, search = SearchStrategy.CURRENT)
    public ExecutorService nettyLoomDispatchExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    public HttpRequestDispatcher httpRequestDispatcher(DispatcherServlet dispatcherServlet,
                                                       NettyServletContext nettyServletContext) {
        return new SpringHttpRequestDispatcher(dispatcherServlet, nettyServletContext);
    }
}
