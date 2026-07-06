package io.github.azholdaspaev.nettyloomspring.autoconfigure.initializer;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link ServletContextInitializer} registered on the factory via the inherited
 * {@code addInitializers(...)} (the supported {@link WebServerFactoryCustomizer} extension point) must
 * actually run at startup, alongside the container-supplied initializers.
 */
@SpringBootTest(
    classes = {SmokeNettyLoomApplication.class, FactoryInitializerTest.FactoryInitializerConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class FactoryInitializerTest {

    @Test
    void factoryRegisteredInitializerRuns() {
        assertTrue(FactoryInitializerConfig.INITIALIZED.get(),
            "initializer added via factory.addInitializers should have run during getWebServer()");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FactoryInitializerConfig {

        static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

        @Bean
        WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> initializerRegisteringCustomizer() {
            return factory -> factory.addInitializers(servletContext -> INITIALIZED.set(true));
        }
    }
}
