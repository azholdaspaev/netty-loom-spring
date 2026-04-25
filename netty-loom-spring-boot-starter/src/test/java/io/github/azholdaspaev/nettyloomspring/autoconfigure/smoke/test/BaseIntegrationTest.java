package io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.test;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;

@AutoConfigureRestTestClient
@SpringBootTest(
    classes = SmokeNettyLoomApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public class BaseIntegrationTest {

}
