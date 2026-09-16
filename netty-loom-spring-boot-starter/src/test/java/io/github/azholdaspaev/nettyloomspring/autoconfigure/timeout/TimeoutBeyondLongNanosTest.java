package io.github.azholdaspaev.nettyloomspring.autoconfigure.timeout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static io.github.azholdaspaev.nettyloomspring.autoconfigure.support.NettyLoomApplications.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One context per property rather than one carrying both: the sites fail at different moments -- the
 * read timeout is converted once in the bean method, the write-stall timeout per accepted connection
 * inside the pipeline step, where a throw leaves the port bound and every request unanswered (#326).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TimeoutBeyondLongNanosTest {

    private static final String BEYOND_LONG_NANOS = "200000d";

    @Test
    void shouldStartWhenReadTimeoutExceedsLongNanos() throws Exception {
        assertServesRequest("server.netty.read-timeout=" + BEYOND_LONG_NANOS);
    }

    @Test
    void shouldServeRequestWhenWriteStallTimeoutExceedsLongNanos() throws Exception {
        assertServesRequest("server.netty.write-stall-timeout=" + BEYOND_LONG_NANOS);
    }

    private static void assertServesRequest(String property) throws Exception {
        try (ConfigurableApplicationContext context = run(property)) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/get")).build(),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(),
                "a timeout past Long.MAX_VALUE nanoseconds must still start the server and serve a request under "
                    + property);
        }
    }
}
