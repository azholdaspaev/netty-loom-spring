package io.github.azholdaspaev.nettyloomspring.autoconfigure.samesite;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.ResponseCookies;

import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

final class SetCookieLines {

    private SetCookieLines() {
    }

    static String fetch(RestTestClient restTestClient, String uri, String name) {
        String line = ResponseCookies.lineFor(restTestClient.get().uri(uri)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult()
            .getResponseHeaders(), name);
        assertNotNull(line, "no Set-Cookie for " + name + " from " + uri);
        return line;
    }
}
