package io.github.azholdaspaev.nettyloomspring.autoconfigure.ssl;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLS is not implemented yet (issue #16). Because the factory is now a
 * {@code ConfigurableServletWebServerFactory}, Boot binds {@code server.ssl.*} onto it; the server must
 * fail fast at startup rather than silently serve plaintext while appearing TLS-configured.
 */
class SslNotSupportedTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldFailFastWhenSslEnabled() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
            new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
                .properties("server.port=0", "server.ssl.enabled=true")
                .run());

        assertTrue(messageChainMentions(failure, "issue #16"),
            "startup failure should point at issue #16; was: " + failure);
    }

    private static boolean messageChainMentions(Throwable throwable, String needle) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
