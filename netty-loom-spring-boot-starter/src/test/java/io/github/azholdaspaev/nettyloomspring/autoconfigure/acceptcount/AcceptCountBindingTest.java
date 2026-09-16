package io.github.azholdaspaev.nettyloomspring.autoconfigure.acceptcount;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.NettyLoomAutoConfiguration;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME;

class AcceptCountBindingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void shouldApplyAcceptCountPropertyToListenSocket() {
        ChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
            .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class))
            .withBean(DEFAULT_DISPATCHER_SERVLET_BEAN_NAME, DispatcherServlet.class, () -> mock(DispatcherServlet.class))
            .withBean(HttpConnectionRegistry.class, () -> new HttpConnectionRegistry(connections))
            .withPropertyValues("server.port=0", "server.netty.accept-count=7")
            .run(context -> {
                int port = context.getSourceApplicationContext(AnnotationConfigServletWebServerApplicationContext.class)
                    .getWebServer().getPort();
                try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
                    client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).timeout(TIMEOUT).build(),
                        HttpResponse.BodyHandlers.discarding());

                    Channel connection = connections.iterator().next();
                    assertEquals(7, connection.parent().config().getOption(ChannelOption.SO_BACKLOG),
                        "server.netty.accept-count must reach the listen socket's SO_BACKLOG option");
                }
            });
    }
}
