package io.github.azholdaspaev.nettyloomspring.autoconfigure.ssl;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.ThrowableChains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLS is not implemented yet (issue #16): the server must fail startup rather than serve plaintext.
 */
class SslNotSupportedTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldFailFastWhenSslEnabled() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
            new SpringApplicationBuilder(SmokeNettyLoomApplication.class)
                .properties("server.port=0", "server.ssl.enabled=true")
                .run());

        assertTrue(ThrowableChains.chainMentions(failure, "issue #16"),
            "startup failure should point at issue #16; was: " + failure);
    }
}
