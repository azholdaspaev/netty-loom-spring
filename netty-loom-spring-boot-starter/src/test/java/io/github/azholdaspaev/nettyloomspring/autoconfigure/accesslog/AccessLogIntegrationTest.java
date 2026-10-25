package io.github.azholdaspaev.nettyloomspring.autoconfigure.accesslog;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.forwardheaders.app.ForwardHeadersTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpAccessLogHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A raw socket connects from 127.0.0.1, which the default server.netty.internal-proxies trusts, so the
 * request arrives as if from a proxy on the same host.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AccessLogIntegrationTest {

    private static final String ACCESS_LOGGER = HttpAccessLogHandler.class.getName();

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldWriteFieldsAsEcsMembersWithForwardedClient(CapturedOutput output) throws Exception {
        try (var context = boot("server.netty.access-log.enabled=true", "logging.structured.format.console=ecs",
            "server.forward-headers-strategy=native")) {
            get(context, "/origin?probe=1", "X-Forwarded-For: 198.51.100.1", "User-Agent: probe/1.0");

            /*
             * Polled: the line is written once the last write completes on the event loop, which can be
             * after the client has already read the whole response.
             */
            JsonNode line = await().atMost(Duration.ofSeconds(10)).until(() -> accessLines(output), lines -> !lines.isEmpty())
                .getFirst();

            assertEquals("198.51.100.1", line.path("remote_ip").asString(),
                "behind a trusted proxy the client is the forwarded one, not the proxy; line: " + line);
            assertEquals("GET", line.path("method").asString(), "line: " + line);
            assertEquals("/origin", line.path("path").asString(), "line: " + line);
            assertEquals("probe=1", line.path("query").asString(), "line: " + line);
            assertEquals("probe/1.0", line.path("user_agent").asString(), "line: " + line);
            assertEquals(200, line.path("status").asInt(), "line: " + line);
            assertTrue(line.path("status").isNumber() && line.path("bytes_out").isNumber()
                    && line.path("duration_ms").isNumber(),
                "the numeric fields are JSON numbers, which a log pipeline can aggregate; line: " + line);
            assertTrue(line.path("bytes_out").asLong() > 0, "line: " + line);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldWriteNoLineByDefault(CapturedOutput output) throws Exception {
        try (var context = boot("logging.structured.format.console=ecs")) {
            get(context, "/origin");

            assertEquals(List.of(), accessLines(output), "the access log is opt-in");
        }
    }

    private static List<JsonNode> accessLines(CapturedOutput output) {
        ObjectMapper json = new ObjectMapper();
        return output.getOut().lines()
            .filter(line -> line.startsWith("{"))
            .map(json::readTree)
            .filter(line -> ACCESS_LOGGER.equals(line.path("log").path("logger").asString()))
            .toList();
    }

    private static ConfigurableApplicationContext boot(String... properties) {
        List<String> all = new ArrayList<>(List.of("server.port=0"));
        all.addAll(List.of(properties));
        return new SpringApplicationBuilder(ForwardHeadersTestApplication.class)
            .properties(all.toArray(String[]::new)).run();
    }

    private static void get(ConfigurableApplicationContext context, String path, String... headers)
        throws IOException {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        try (Socket socket = RawHttpClient.connect(port, Duration.ofSeconds(5))) {
            List<String> all = new ArrayList<>(List.of("Host: 127.0.0.1", "Connection: close"));
            all.addAll(List.of(headers));
            RawHttpClient.send(socket, "GET " + path + " HTTP/1.1", all.toArray(String[]::new));
            RawHttpResponse.read(socket.getInputStream()).readBody();
        }
    }
}
