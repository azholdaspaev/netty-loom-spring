package io.github.azholdaspaev.nettyloomspring.autoconfigure.metrics;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.NettyLoomAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME;

class NettyServerMetricsAutoConfigurationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class, NettyServerMetricsAutoConfiguration.class))
        .withBean(DEFAULT_DISPATCHER_SERVLET_BEAN_NAME, DispatcherServlet.class, () -> mock(DispatcherServlet.class));

    @Test
    void shouldDeclareServerMetricsWhenMicrometerIsPresent() {
        runner.run(context -> assertThat(context).hasSingleBean(NettyServerMetrics.class));
    }

    @Test
    void shouldSkipServerMetricsWhenMicrometerIsAbsent() {
        runner.withClassLoader(new FilteredClassLoader(MeterBinder.class))
            .run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(NettyServerMetrics.class));
    }

    @Test
    void shouldSkipServerMetricsWhenTomcatServes() {
        runner.withConfiguration(AutoConfigurations.of(TomcatServletWebServerAutoConfiguration.class))
            .run(context -> assertThat(context).doesNotHaveBean(NettyServerMetrics.class));
    }

    @Test
    void shouldSkipServerMetricsWhenUserDeclaresServerFactory() {
        runner.withBean("userBean", ServletWebServerFactory.class, () -> mock(ServletWebServerFactory.class))
            .run(context -> assertThat(context).doesNotHaveBean(NettyServerMetrics.class));
    }

    @Test
    void shouldUseUserBeanWithoutPrimary() {
        NettyServerMetrics userBean = mock(NettyServerMetrics.class);
        runner.withBean("userBean", NettyServerMetrics.class, () -> userBean)
            .run(context -> assertThat(context)
                .hasSingleBean(NettyServerMetrics.class)
                .getBean(NettyServerMetrics.class).isSameAs(userBean));
    }

    @Test
    void shouldReportOpenConnectionAndDispatchWhileServletRuns() throws Exception {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        CompletableFuture<double[]> gaugesDuringService = new CompletableFuture<>();
        DispatcherServlet servlet = mock(DispatcherServlet.class);
        doAnswer(invocation -> {
            try {
                gaugesDuringService.complete(new double[] {
                    meterRegistry.get("netty.server.connections.active").gauge().value(),
                    meterRegistry.get("netty.server.dispatches.active").gauge().value()
                });
            } catch (RuntimeException e) {
                gaugesDuringService.completeExceptionally(e);
            }
            return null;
        }).when(servlet).service(any(ServletRequest.class), any(ServletResponse.class));
        new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
            .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class, NettyServerMetricsAutoConfiguration.class))
            .withBean(DEFAULT_DISPATCHER_SERVLET_BEAN_NAME, DispatcherServlet.class, () -> servlet)
            .withPropertyValues("server.port=0")
            .run(context -> {
                context.getBean(NettyServerMetrics.class).bindTo(meterRegistry);
                int port = context.getSourceApplicationContext(AnnotationConfigServletWebServerApplicationContext.class)
                    .getWebServer().getPort();
                try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
                    client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).timeout(TIMEOUT).build(),
                        HttpResponse.BodyHandlers.discarding());
                }

                double[] gauges = gaugesDuringService.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(gauges[0]).as("the connection carrying the request must count as open, and only once").isEqualTo(1);
                assertThat(gauges[1]).as("the dispatch running the servlet must count as active, and only once").isEqualTo(1);
            });
    }
}
