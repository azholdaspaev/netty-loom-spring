package io.github.azholdaspaev.nettyloomspring.autoconfigure.contextpath;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.contextpath.app.ContextPathTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #49 review finding #1: the configured {@code server.servlet.context-path} must be visible to
 * startup initializers. The Jakarta contract requires {@link jakarta.servlet.ServletContext#getContextPath()}
 * to be valid during {@link org.springframework.boot.web.servlet.ServletContextInitializer#onStartup},
 * so a factory-registered initializer must observe {@code /app}, not the default {@code ""}.
 */
@SpringBootTest(
    classes = {ContextPathTestApplication.class, ContextPathStartupTest.CaptureConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = "server.servlet.context-path=/app")
class ContextPathStartupTest {

    @Test
    void contextPathIsVisibleToStartupInitializers() {
        assertEquals("/app", CaptureConfig.CAPTURED.get(),
            "an initializer registered on the factory should see the configured context path at onStartup");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfig {

        static final AtomicReference<String> CAPTURED = new AtomicReference<>();

        @Bean
        WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> contextPathCapturingCustomizer() {
            return factory -> factory.addInitializers(servletContext -> CAPTURED.set(servletContext.getContextPath()));
        }
    }
}
