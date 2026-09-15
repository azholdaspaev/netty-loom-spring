package io.github.azholdaspaev.nettyloomspring.autoconfigure;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.NettyWebServerFactory;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.SessionStoreLifecycle;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineDefinition;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyIoHandlerFactory;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NettyLoomAutoConfigurationTest {

    private static final String DISPATCH_EXECUTOR_BEAN = "nettyLoomDispatchExecutor";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class))
        .withBean(DispatcherServlet.class);

    @Test
    void shouldRegisterNettyWebServerFactoryInServletWebApplication() {
        runner.run(context -> assertThat(context)
            .hasSingleBean(ServletWebServerFactory.class)
            .hasSingleBean(NettyWebServerFactory.class));
    }

    @Test
    void shouldBackOffWhenApplicationIsNotServletWeb() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class))
            .withBean(DispatcherServlet.class)
            .run(context -> assertThat(context)
                .doesNotHaveBean(NettyWebServerFactory.class)
                .doesNotHaveBean(HttpRequestDispatcher.class));
    }

    @Test
    void shouldBackOffWhenNettyIsAbsentFromClasspath() {
        runner.withClassLoader(new FilteredClassLoader(HttpServerCodec.class))
            .run(context -> assertThat(context)
                .doesNotHaveBean(NettyWebServerFactory.class)
                .doesNotHaveBean(HttpRequestDispatcher.class));
    }

    @Test
    void shouldBackOffWhenUserDeclaresServletWebServerFactory() {
        ServletWebServerFactory userFactory = mock(ServletWebServerFactory.class);
        runner.withBean("userBean", ServletWebServerFactory.class, () -> userFactory)
            .run(context -> assertThat(context)
                .doesNotHaveBean(NettyWebServerFactory.class)
                .getBean(ServletWebServerFactory.class).isSameAs(userFactory));
    }

    @Test
    void shouldYieldToTomcatAutoConfigurationOnSharedClasspath() {
        runner.withConfiguration(AutoConfigurations.of(TomcatServletWebServerAutoConfiguration.class))
            .run(context -> assertThat(context)
                .doesNotHaveBean(NettyWebServerFactory.class)
                .getBean(ServletWebServerFactory.class).isInstanceOf(TomcatServletWebServerFactory.class));
    }

    @ParameterizedTest
    @ValueSource(classes = {
        NettyIoHandlerFactory.class,
        NettyServletContext.class,
        SessionStoreLifecycle.class,
        HttpConnectionRegistry.class,
        NettyServerChannelInitializer.class,
        NettyPipelineDefinition.class,
        HttpRequestDispatcher.class
    })
    void shouldUseUserBeanWithoutPrimary(Class<?> type) {
        assertUserBeanWins(type);
    }

    private <T> void assertUserBeanWins(Class<T> type) {
        T userBean = mock(type);
        runner.withBean("userBean", type, () -> userBean)
            .run(context -> assertThat(context)
                .hasSingleBean(type)
                .getBean(type).isSameAs(userBean));
    }

    @Test
    void shouldUseUserExecutorNamedNettyLoomDispatchExecutor() {
        ExecutorService userExecutor = mock(ExecutorService.class);
        runner.withBean(DISPATCH_EXECUTOR_BEAN, ExecutorService.class, () -> userExecutor)
            .run(context -> assertThat(context)
                .hasSingleBean(ExecutorService.class)
                .getBean(DISPATCH_EXECUTOR_BEAN).isSameAs(userExecutor));
    }

    @Test
    void shouldDispatchOnNamedExecutorWhenUserExecutorIsPrimary() {
        ExecutorService namedExecutor = mock(ExecutorService.class);
        ExecutorService primaryExecutor = mock(ExecutorService.class);
        runner.withBean(DISPATCH_EXECUTOR_BEAN, ExecutorService.class, () -> namedExecutor)
            .withBean("batchPool", ExecutorService.class, () -> primaryExecutor, definition -> definition.setPrimary(true))
            .run(context -> {
                EmbeddedChannel channel = new EmbeddedChannel();
                context.getBean(NettyPipelineDefinition.class).applyTo(channel.pipeline());
                channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));

                verify(namedExecutor).execute(any());
                verifyNoInteractions(primaryExecutor);
                channel.finishAndReleaseAll();
            });
    }

    @Test
    void shouldKeepOwnExecutorWhenUserDeclaresAnotherExecutorService() {
        runner.withBean("workers", ExecutorService.class, () -> mock(ExecutorService.class))
            .run(context -> assertThat(context)
                .hasSingleBean(NettyWebServerFactory.class)
                .hasBean(DISPATCH_EXECUTOR_BEAN));
    }
}
