package io.azholdaspaev.nettyloom.autoconfigure.smoke.test;

import io.azholdaspaev.nettyloom.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;

@AutoConfigureRestTestClient
@SpringBootTest(
    classes = SmokeNettyLoomApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public class BaseIntegrationTest {

}
