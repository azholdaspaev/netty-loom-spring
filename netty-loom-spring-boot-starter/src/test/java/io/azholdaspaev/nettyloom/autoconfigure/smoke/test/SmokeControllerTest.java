package io.azholdaspaev.nettyloom.autoconfigure.smoke.test;

import io.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SmokeNettyLoomApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmokeControllerTest {

    @Test
    void contextLoads() {
    }
}
