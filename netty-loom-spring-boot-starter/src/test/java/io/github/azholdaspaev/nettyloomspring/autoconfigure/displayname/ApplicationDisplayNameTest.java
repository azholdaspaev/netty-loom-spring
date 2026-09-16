package io.github.azholdaspaev.nettyloomspring.autoconfigure.displayname;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications.run;
import static io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications.servletContext;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code server.servlet.application-display-name} must reach {@code getServletContextName()} (issue #86),
 * as it does under Tomcat.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ApplicationDisplayNameTest {

    @Test
    void shouldUseBootsDefaultDisplayNameAsServletContextName() {
        try (var context = run()) {
            assertEquals("application", servletContext(context).getServletContextName(),
                "Boot defaults server.servlet.application-display-name to 'application', so that, not the "
                    + "container's own constant, is what a Boot app must see");
        }
    }

    @Test
    void shouldUseConfiguredDisplayNameAsServletContextName() {
        try (var context = run("server.servlet.application-display-name=orders")) {
            assertEquals("orders", servletContext(context).getServletContextName());
        }
    }

    @Test
    void shouldExposeDisplayNameToStartupInitializers() {
        try (var _ = new SpringApplicationBuilder(SmokeNettyLoomApplication.class, CaptureConfig.class)
            .properties("server.port=0", "server.servlet.application-display-name=orders")
            .run()) {
            assertEquals("orders", CaptureConfig.CAPTURED.get(),
                "an initializer registered on the factory should see the configured display name at onStartup");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CaptureConfig {

        static final AtomicReference<String> CAPTURED = new AtomicReference<>();

        @Bean
        WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> displayNameCapturingCustomizer() {
            return factory -> factory.addInitializers(
                servletContext -> CAPTURED.set(servletContext.getServletContextName()));
        }
    }
}
