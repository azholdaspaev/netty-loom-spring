package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyServletWebServerFactoryTest {

    @Test
    void shouldRejectSslWhenNoContainerSetEnvironment() {
        NettyServletWebServerFactory factory = new NettyServletWebServerFactory(null, null, null, null, null, null);
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        factory.setSsl(ssl);

        WebServerException failure = assertThrows(WebServerException.class, factory::getWebServer,
            "registerSingleton and direct construction never call setEnvironment, yet TLS must still fail fast");
        assertTrue(failure.getMessage().contains("issue #16"), "the failure must point at issue #16, was: " + failure);
    }
}
