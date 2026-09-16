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
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

import static io.github.azholdaspaev.nettyloomspring.autoconfigure.NettyLoomAutoConfiguration.DISPATCH_EXECUTOR_BEAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME;

class NettyLoomAutoConfigurationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebApplicationContextRunner runner = newRunnerWithServlet(mock(DispatcherServlet.class));

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

    @ParameterizedTest
    @ValueSource(strings = {
        "server.netty.max-header-size",
        "server.netty.max-initial-line-length",
        "server.netty.max-chunk-size"
    })
    void shouldFailStartupNamingPropertyWhenCodecLimitDoesNotFitInt(String property) {
        runner.withPropertyValues(property + "=3GB")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining(property));
    }

    @ParameterizedTest
    @CsvSource({
        "server.netty.max-header-size, 0B",
        "server.netty.max-header-size, -1B",
        "server.netty.max-initial-line-length, 0B",
        "server.netty.max-initial-line-length, -1B",
        "server.netty.max-chunk-size, 0B",
        "server.netty.max-chunk-size, -1B",
        "server.netty.max-http-body-size, 0B",
        "server.netty.max-http-body-size, -1B"
    })
    void shouldFailStartupNamingPropertyWhenSizeLimitIsNotPositive(String property, String value) {
        runner.withPropertyValues(property + "=" + value)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining(property));
    }

    @Test
    void shouldFailStartupOnNonPositiveLimitWhenUserDeclaresPipeline() {
        runner.withBean("userBean", NettyPipelineDefinition.class, () -> mock(NettyPipelineDefinition.class))
            .withPropertyValues("server.netty.max-header-size=0B")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("server.netty.max-header-size"));
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

    @Test
    void shouldDispatchToChildServletInChildContext() throws Exception {
        DispatcherServlet parentServlet = newServletWriting("parent");
        DispatcherServlet childServlet = newServletWriting("child");
        newRunnerWithServlet(parentServlet).run(parent ->
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class))
                .withBean(DEFAULT_DISPATCHER_SERVLET_BEAN_NAME, DispatcherServlet.class, () -> childServlet)
                .withPropertyValues("server.port=0")
                .withParent(parent)
                .run(child -> {
                    int port = child.getSourceApplicationContext(AnnotationConfigServletWebServerApplicationContext.class)
                        .getWebServer().getPort();
                    try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
                        HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).timeout(TIMEOUT).build(),
                            HttpResponse.BodyHandlers.ofString());
                        assertThat(response.body()).isEqualTo("child");
                    }
                    verify(parentServlet, never()).service(any(ServletRequest.class), any(ServletResponse.class));
                }));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "nettyIoHandlerFactory",
        "nettyServletContext",
        "sessionStoreLifecycle",
        "httpConnectionRegistry",
        "nettyServerChannelInitializer",
        "nettyPipelineDefinition",
        "httpRequestDispatcher",
        DISPATCH_EXECUTOR_BEAN
    })
    void shouldOwnDefaultBeanInChildContext(String beanName) {
        newRunnerWithServlet(mock(DispatcherServlet.class)).run(parent ->
            newRunnerWithServlet(mock(DispatcherServlet.class)).withParent(parent).run(child ->
                assertThat(child.getBean(beanName))
                    .as("child's %s must be its own, not the parent's", beanName)
                    .isNotSameAs(parent.getBean(beanName))));
    }

    @Test
    void shouldResolveOwnServletContextWhenParentNamesOneDifferently() {
        newRunnerWithServlet(mock(DispatcherServlet.class))
            .withBean("customServletContext", NettyServletContext.class, () -> mock(NettyServletContext.class))
            .run(parent -> newRunnerWithServlet(mock(DispatcherServlet.class)).withParent(parent).run(child ->
                assertThat(child).hasNotFailed()
                    .getBean("nettyServletContext").isNotSameAs(parent.getBean("customServletContext"))));
    }

    @Test
    void shouldDispatchThroughChildOverrideUnderDefaultBeanName() throws Exception {
        HttpRequestDispatcher childDispatcher = mock(HttpRequestDispatcher.class);
        newRunnerWithServlet(mock(DispatcherServlet.class)).run(parent ->
            newRunnerWithServlet(mock(DispatcherServlet.class))
                .withBean("httpRequestDispatcher", HttpRequestDispatcher.class, () -> childDispatcher)
                .withBean(DISPATCH_EXECUTOR_BEAN, ExecutorService.class, NettyLoomAutoConfigurationTest::newInlineExecutor)
                .withParent(parent)
                .run(child -> {
                    EmbeddedChannel channel = new EmbeddedChannel();
                    child.getBean(NettyPipelineDefinition.class).applyTo(channel.pipeline());
                    channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));

                    verify(childDispatcher).handle(any(), any(), any(), any());
                    channel.finishAndReleaseAll();
                }));
    }

    @Test
    void shouldLeaveParentExecutorAndSessionsOpenAfterChildCloses() {
        newRunnerWithServlet(mock(DispatcherServlet.class)).run(parent -> {
            newRunnerWithServlet(mock(DispatcherServlet.class)).withParent(parent).run(child -> { });

            assertThat(parent.getBean(DISPATCH_EXECUTOR_BEAN, ExecutorService.class).isShutdown())
                .as("closing the child must not shut down the parent's dispatch executor").isFalse();
            assertThatCode(() -> parent.getBean("nettyServletContext", NettyServletContext.class).getSessionManager().create())
                .as("closing the child must not close the parent's session store").doesNotThrowAnyException();
        });
    }

    @Test
    void shouldDestroyDispatcherServletOnceContextCloses() {
        DispatcherServlet servlet = mock(DispatcherServlet.class);
        new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
            .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class))
            .withBean(DEFAULT_DISPATCHER_SERVLET_BEAN_NAME, DispatcherServlet.class, () -> servlet)
            .withPropertyValues("server.port=0")
            .run(context -> verify(servlet, never()).destroy());

        verify(servlet).destroy();
    }

    private static WebApplicationContextRunner newRunnerWithServlet(DispatcherServlet servlet) {
        return new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class))
            .withBean(DEFAULT_DISPATCHER_SERVLET_BEAN_NAME, DispatcherServlet.class, () -> servlet);
    }

    private static ExecutorService newInlineExecutor() {
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any());
        return executor;
    }

    private static DispatcherServlet newServletWriting(String body) throws Exception {
        DispatcherServlet servlet = mock(DispatcherServlet.class);
        doAnswer(invocation -> {
            invocation.<ServletResponse>getArgument(1).getWriter().write(body);
            return null;
        }).when(servlet).service(any(ServletRequest.class), any(ServletResponse.class));
        return servlet;
    }
}
