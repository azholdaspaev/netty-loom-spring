package io.github.azholdaspaev.nettyloomspring.autoconfigure.displayname;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code server.servlet.application-display-name} must reach {@code getServletContextName()} (issue #86),
 * as it does under Tomcat.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ApplicationDisplayNameTest {

    private static ConfigurableApplicationContext run(String... properties) {
        String[] all = new String[properties.length + 1];
        all[0] = "server.port=0";
        System.arraycopy(properties, 0, all, 1, properties.length);
        return new SpringApplicationBuilder(SmokeNettyLoomApplication.class).properties(all).run();
    }

    private static NettyServletContext servletContext(ConfigurableApplicationContext context) {
        // By name, not type; SessionConfigurationTest.servletContext says why.
        return context.getBean("nettyServletContext", NettyServletContext.class);
    }

    @Test
    void bootsDefaultDisplayNameIsTheServletContextName() {
        try (var context = run()) {
            assertEquals("application", servletContext(context).getServletContextName(),
                "Boot defaults server.servlet.application-display-name to 'application', so that, not the "
                    + "container's own constant, is what a Boot app must see");
        }
    }

    @Test
    void aConfiguredDisplayNameIsTheServletContextName() {
        try (var context = run("server.servlet.application-display-name=orders")) {
            assertEquals("orders", servletContext(context).getServletContextName());
        }
    }
}
