package io.github.azholdaspaev.nettyloomspring.autoconfigure.mimemappings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications.run;
import static io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications.servletContext;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boot's {@code MimeMappings} must reach {@code ServletContext.getMimeType} (issue #59), as they do under
 * Tomcat: the defaults for the welcome page, and {@code server.mime-mappings.*} on top of them.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MimeMappingsTest {

    @Test
    void shouldAnswerGetMimeTypeFromBootsDefaultMappings() {
        try (var context = run()) {
            assertEquals("text/html", servletContext(context).getMimeType("index.html"));
            assertEquals("image/svg+xml", servletContext(context).getMimeType("logo.svg"),
                "a type outside Boot's short common table must come from its full mime-mappings.properties");
        }
    }

    @Test
    void shouldAnswerGetMimeTypeFromConfiguredMapping() {
        try (var context = run("server.mime-mappings.custom=application/x-custom")) {
            assertEquals("application/x-custom", servletContext(context).getMimeType("report.custom"));
            assertEquals("text/html", servletContext(context).getMimeType("index.html"),
                "server.mime-mappings.* adds to Boot's defaults rather than replacing them");
        }
    }
}
