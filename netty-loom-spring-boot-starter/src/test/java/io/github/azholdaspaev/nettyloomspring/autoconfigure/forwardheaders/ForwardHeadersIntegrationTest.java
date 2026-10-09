package io.github.azholdaspaev.nettyloomspring.autoconfigure.forwardheaders;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.forwardheaders.app.ForwardHeadersTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A raw socket connects from 127.0.0.1, which the default server.netty.internal-proxies trusts, so each
 * request below arrives as if from a proxy on the same host.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ForwardHeadersIntegrationTest {

    private static final String NATIVE = "server.forward-headers-strategy=native";
    private static final String[] PROXIED = {
        "X-Forwarded-For: 198.51.100.1", "X-Forwarded-Proto: https", "X-Forwarded-Host: app.example"
    };

    @Test
    void shouldReportForwardedOriginUnderNativeStrategy() throws Exception {
        try (var context = boot(NATIVE)) {
            assertEquals("https|true|app.example|443|198.51.100.1", get(context, "/origin", PROXIED).body());
        }
    }

    @Test
    void shouldRedirectToForwardedUrlUnderNativeStrategy() throws Exception {
        try (var context = boot(NATIVE)) {
            assertEquals("https://app.example/origin/redirect", get(context, "/origin/redirect", PROXIED).location());
            assertEquals("/origin", get(context, "/origin/relative", PROXIED).location(),
                "a relative Location stays relative, as Tomcat writes it with useRelativeRedirects=true");
        }
    }

    @Test
    void shouldKeepInternalHostUnderProtoOnlyForwarding() throws Exception {
        try (var context = boot(NATIVE)) {
            assertEquals("https://127.0.0.1/origin/redirect",
                get(context, "/origin/redirect", "X-Forwarded-Proto: https").location());
        }
    }

    @Test
    void shouldMakeSessionCookieSecureUnderNativeStrategy() throws Exception {
        try (var context = boot(NATIVE)) {
            String setCookie = get(context, "/origin/session", PROXIED).setCookie();

            assertTrue(setCookie.startsWith("JSESSIONID=") && setCookie.contains("; Secure"), "Actual: " + setCookie);
        }
    }

    @Test
    void shouldIgnoreForwardedHeadersWhenStrategyIsUnset() throws Exception {
        try (var context = boot()) {
            assertDirectOrigin(context);
        }
    }

    @Test
    void shouldIgnoreForwardedHeadersUnderNoneStrategy() throws Exception {
        try (var context = boot("server.forward-headers-strategy=none")) {
            assertDirectOrigin(context);
        }
    }

    @Test
    void shouldApplyFrameworkStrategyThroughForwardedHeaderFilter() throws Exception {
        try (var context = boot("server.forward-headers-strategy=framework")) {
            assertEquals("https|true|app.example|443|198.51.100.1", get(context, "/origin", PROXIED).body());
        }
    }

    @Test
    void shouldIgnoreForwardedHeadersFromPeerOutsideInternalProxies() throws Exception {
        try (var context = boot(NATIVE, "server.netty.internal-proxies=10.0.0.0/8")) {
            assertEquals("http|false|127.0.0.1|80|127.0.0.1", get(context, "/origin", PROXIED).body());
        }
    }

    @Test
    void shouldReadForwardedHeaderWhenConfigured() throws Exception {
        try (var context = boot(NATIVE, "server.netty.forwarded-headers=forwarded")) {
            assertEquals("https|true|app.example|443|198.51.100.1",
                get(context, "/origin", "Forwarded: for=198.51.100.1;proto=https;host=app.example").body());
            assertEquals("http|false|127.0.0.1|80|127.0.0.1", get(context, "/origin", PROXIED).body(),
                "under forwarded-headers=forwarded the X-Forwarded-* family is not read");
        }
    }

    private static void assertDirectOrigin(ConfigurableApplicationContext context) throws IOException {
        assertEquals("http|false|127.0.0.1|80|127.0.0.1", get(context, "/origin", PROXIED).body());
        String setCookie = get(context, "/origin/session", PROXIED).setCookie();
        assertFalse(setCookie.contains("Secure"), "a direct origin leaves the cookie plain; Actual: " + setCookie);
    }

    private static ConfigurableApplicationContext boot(String... properties) {
        List<String> all = new ArrayList<>(List.of("server.port=0"));
        all.addAll(List.of(properties));
        return new SpringApplicationBuilder(ForwardHeadersTestApplication.class)
            .properties(all.toArray(String[]::new)).run();
    }

    private record Reply(String location, String setCookie, String body) {
    }

    private static Reply get(ConfigurableApplicationContext context, String path, String... headers)
        throws IOException {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        try (Socket socket = RawHttpClient.connect(port, Duration.ofSeconds(5))) {
            List<String> all = new ArrayList<>(List.of("Host: 127.0.0.1", "Connection: close"));
            all.addAll(List.of(headers));
            RawHttpClient.send(socket, "GET " + path + " HTTP/1.1", all.toArray(String[]::new));
            RawHttpResponse response = RawHttpResponse.read(socket.getInputStream());
            return new Reply(response.header("location"), response.header("set-cookie"), response.readBody());
        }
    }
}
