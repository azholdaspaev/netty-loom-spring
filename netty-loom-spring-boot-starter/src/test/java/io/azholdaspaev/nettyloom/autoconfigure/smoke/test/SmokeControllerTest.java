package io.azholdaspaev.nettyloom.autoconfigure.smoke.test;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

class SmokeControllerTest extends BaseIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldHandleGetRequest() {
        restTestClient.get().uri("/get")
            .exchange()
            .expectStatus()
            .isOk();
    }
}
