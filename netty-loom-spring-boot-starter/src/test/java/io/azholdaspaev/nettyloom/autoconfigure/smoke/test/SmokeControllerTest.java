package io.azholdaspaev.nettyloom.autoconfigure.smoke.test;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

class SmokeControllerTest extends BaseIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Disabled
    void shouldHandleGetRequest() {
        restTestClient.get().uri("/get")
            .exchange()
            .expectStatus()
            .isOk();
    }
}
