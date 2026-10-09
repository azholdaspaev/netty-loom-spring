package io.github.azholdaspaev.nettyloomspring.autoconfigure.forwardheaders;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.NettyLoomAutoConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.DispatcherServlet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ForwardedProxyBindingTest {

    @ParameterizedTest
    @ValueSource(strings = {"proxy.example", "10.0.0.0/33", "::1/129"})
    void shouldFailStartupWithInvalidProxyProperty(String value) {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class))
            .withBean("dispatcherServlet", DispatcherServlet.class, () -> mock(DispatcherServlet.class))
            .withPropertyValues("server.forward-headers-strategy=none",
                "server.netty.forwarded-trusted-proxies=" + value)
            .run(context -> {
                Throwable failure = context.getStartupFailure();
                assertNotNull(failure);
                while (failure.getCause() != null) {
                    failure = failure.getCause();
                }
                assertTrue(failure.getMessage().contains("forwardedTrustedProxies"), failure.toString());
            });
    }
}
