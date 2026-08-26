package io.github.azholdaspaev.nettyloomspring.autoconfigure.forward;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.forward.app.ForwardTestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AutoConfigureRestTestClient
@SpringBootTest(
    classes = ForwardTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ForwardIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aControllerForwardingToAnotherControllerReturnsTheTargetsBody() {
        restTestClient.get().uri("/forward/source")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body ->
                assertTrue(body.startsWith("target uri=/forward/target"), "got " + body));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void theTargetSeesTheForwardAttributesAndTheDispatchPathsQuery() {
        restTestClient.get().uri("/forward/manual")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .isEqualTo("target uri=/forward/target who=target from=/forward/manual trace=request,forward");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bytesWrittenBeforeTheForwardAreDiscarded() {
        restTestClient.get().uri("/forward/dirty")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body ->
                assertFalse(body.contains("discarded"), "the forward resets the buffer; got " + body));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void forwardAfterCommitIsRejected() {
        restTestClient.get().uri("/forward/committed")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("committed-rejected");
    }
}
