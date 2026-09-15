package io.github.azholdaspaev.nettyloomspring.autoconfigure.displayname;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

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
}
