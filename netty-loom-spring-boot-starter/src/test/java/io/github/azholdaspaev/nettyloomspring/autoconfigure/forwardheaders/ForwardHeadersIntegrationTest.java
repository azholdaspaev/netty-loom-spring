package io.github.azholdaspaev.nettyloomspring.autoconfigure.forwardheaders;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.forwardheaders.app.ForwardHeadersTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = ForwardHeadersTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.forward-headers-strategy=native")
class ForwardHeadersIntegrationTest {

    private static final String FORWARDED = "Forwarded: for=203.0.113.7;proto=https;host=app.example";

    @LocalServerPort
    int port;

    @Test
    void shouldResolveOriginBeforeListenersAndKeepSocketIdentity() throws Exception {
        try (Socket socket = connect(port)) {
            var response = exchange(socket, "/origin", FORWARDED);
            assertEquals(200, response.status());
            assertEquals("https|app.example|443|203.0.113.7|0|true|false|https://app.example/origin|null|https",
                response.readBody());
        }
    }

    @Test
    void shouldStopAtUntrustedHopAndIgnoreForgedLeftmostOrigin() throws Exception {
        try (Socket socket = connect(port)) {
            var response = exchange(socket, "/origin",
                "Forwarded: for=198.51.100.8;proto=http;host=forged.example",
                FORWARDED);
            assertTrue(response.readBody().startsWith("https|app.example|443|203.0.113.7|0|true|false|"));
        }
    }

    @Test
    void shouldUseXHeadersAndExplicitPort() throws Exception {
        try (Socket socket = connect(port)) {
            var response = exchange(socket, "/origin", "X-Forwarded-For: 203.0.113.7, 10.0.0.2",
                "X-Forwarded-Proto: https", "X-Forwarded-Host: app.example:8443", "X-Forwarded-Port: 9443");
            assertTrue(response.readBody().startsWith("https|app.example|9443|203.0.113.7|0|true|false|"));
        }
    }

    @Test
    void shouldResetMetadataBetweenKeepAliveRequests() throws Exception {
        try (Socket socket = connect(port)) {
            assertTrue(exchange(socket, "/origin", FORWARDED).readBody().startsWith("https|app.example|443|"));
            assertTrue(exchange(socket, "/origin").readBody().startsWith("http|internal|8080|127.0.0.1|"));
            assertTrue(exchange(socket, "/origin", "Forwarded: for=198.51.100.3;proto=http;host=second.example")
                .readBody().startsWith("http|second.example|80|198.51.100.3|"));
        }
    }

    @Test
    void shouldSecureRotatedCookieAndResolveExternalRedirect() throws Exception {
        try (Socket socket = connect(port)) {
            var session = exchange(socket, "/session", FORWARDED);
            assertTrue(session.header("set-cookie").contains("Secure"));
            session.readBody();
            var redirect = exchange(socket, "/redirect", FORWARDED);
            assertEquals(302, redirect.status());
            assertEquals("https://app.example/target", redirect.header("location"));
            redirect.readBody();
            var direct = exchange(socket, "/session");
            assertFalse(direct.header("set-cookie").contains("Secure"));
            direct.readBody();
        }
    }

    @Test
    void shouldReturn400ForMalformedHeadersWithUnconsumedBody() throws Exception {
        try (Socket socket = connect(port)) {
            RawHttpClient.send(socket, "POST /origin HTTP/1.1", "Host: internal:8080",
                "Forwarded: broken", "Content-Length: 4");
            RawHttpClient.sendBody(socket, "body");
            var response = RawHttpResponse.read(socket.getInputStream());
            assertEquals(400, response.status());
            response.readBody();
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @ParameterizedTest
    @CsvSource({"none, default, http, /target", "unset, default, http, /target",
        "native, empty, http, http://internal:8080/target",
        "native, public, http, http://internal:8080/target",
        "framework, empty, https, https://app.example/target"})
    void shouldApplySelectedStrategyAndProxyTrust(String strategy, String trust, String scheme, String location)
        throws Exception {
        List<String> properties = new ArrayList<>(List.of("server.port=0"));
        if (!strategy.equals("unset")) {
            properties.add("server.forward-headers-strategy=" + strategy);
        }
        if (trust.equals("empty")) {
            properties.add("server.netty.forwarded-trusted-proxies=");
        } else if (trust.equals("public")) {
            properties.add("server.netty.forwarded-trusted-proxies=203.0.113.0/24");
        }
        try (var context = new SpringApplicationBuilder(ForwardHeadersTestApplication.class)
            .properties(properties.toArray(String[]::new)).run();
             Socket socket = connect(context.getEnvironment().getRequiredProperty("local.server.port", Integer.class))) {
            var origin = exchange(socket, "/origin", FORWARDED);
            assertTrue(origin.readBody().startsWith(scheme + "|"));
            var redirect = exchange(socket, "/redirect", FORWARDED);
            assertEquals(location, redirect.header("location"));
            redirect.readBody();
            if (strategy.equals("native")) {
                var malformed = exchange(socket, "/origin", "Forwarded: broken");
                assertEquals(200, malformed.status());
                assertTrue(malformed.readBody().startsWith("http|internal|8080|"));
            }
        }
    }

    private static Socket connect(int port) throws IOException {
        return RawHttpClient.connect(port, Duration.ofSeconds(5));
    }

    private static RawHttpResponse exchange(Socket socket, String path, String... headers) throws IOException {
        List<String> all = new ArrayList<>(List.of("Host: internal:8080"));
        all.addAll(List.of(headers));
        RawHttpClient.send(socket, "GET " + path + " HTTP/1.1", all.toArray(String[]::new));
        return RawHttpResponse.read(socket.getInputStream());
    }
}
