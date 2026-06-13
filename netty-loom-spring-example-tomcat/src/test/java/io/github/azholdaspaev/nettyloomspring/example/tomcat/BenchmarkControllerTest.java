package io.github.azholdaspaev.nettyloomspring.example.tomcat;

import io.github.azholdaspaev.nettyloomspring.example.tomcat.BenchmarkController.WorkResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

@AutoConfigureRestTestClient
@SpringBootTest(
    classes = TomcatExampleApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class BenchmarkControllerTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void pingReturnsPong() {
        restTestClient.get().uri("/ping")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("pong");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void workReturnsJsonAfterSimulatedBlockingCall() {
        restTestClient.get().uri("/work")
            .exchange()
            .expectStatus().isOk()
            .expectBody(WorkResponse.class).isEqualTo(new WorkResponse("ok", 50));
    }
}
