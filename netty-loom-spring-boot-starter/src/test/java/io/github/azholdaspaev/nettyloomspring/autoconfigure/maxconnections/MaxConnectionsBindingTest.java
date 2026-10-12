package io.github.azholdaspaev.nettyloomspring.autoconfigure.maxconnections;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.NettyLoomAutoConfiguration;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME;

class MaxConnectionsBindingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void shouldHoldConnectionOverMaxConnectionsUntilAnotherCloses() {
        new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
            .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class))
            .withBean(DEFAULT_DISPATCHER_SERVLET_BEAN_NAME, DispatcherServlet.class, () -> mock(DispatcherServlet.class))
            .withPropertyValues("server.port=0", "server.netty.max-connections=1")
            .run(context -> {
                int port = context.getSourceApplicationContext(AnnotationConfigServletWebServerApplicationContext.class)
                    .getWebServer().getPort();
                Socket first = RawHttpClient.connect(port, TIMEOUT);
                try {
                    RawHttpClient.send(first, "GET / HTTP/1.1", "Host: localhost");
                    assertEquals(200, RawHttpResponse.read(first.getInputStream()).status());

                    try (Socket queued = RawHttpClient.connect(port, Duration.ofMillis(300))) {
                        RawHttpClient.send(queued, "GET / HTTP/1.1", "Host: localhost");
                        assertThrows(SocketTimeoutException.class, () -> queued.getInputStream().read(),
                            "server.netty.max-connections=1 must keep a second connection waiting while the first is open");

                        first.close();

                        queued.setSoTimeout((int) TIMEOUT.toMillis());
                        assertEquals(200, RawHttpResponse.read(queued.getInputStream()).status(),
                            "the waiting connection must be served once the first one closes");
                    }
                } finally {
                    first.close();
                }
            });
    }
}
