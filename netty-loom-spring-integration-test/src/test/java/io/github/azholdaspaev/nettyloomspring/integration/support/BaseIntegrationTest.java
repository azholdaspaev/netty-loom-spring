package io.github.azholdaspaev.nettyloomspring.integration.support;

import io.github.azholdaspaev.nettyloomspring.integration.RestServiceApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The one context every reference-application test shares: a subclass that declared its own
 * annotations or properties would fork a second cached context, and a second heap to dump.
 */
@AutoConfigureRestTestClient
@SpringBootTest(
    classes = RestServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public abstract class BaseIntegrationTest {

    @Autowired
    protected RestTestClient restTestClient;

    @DynamicPropertySource
    static void registerJwkSetUri(DynamicPropertyRegistry registry) {
        JwkSetServer.registerJwkSetUri(registry);
    }
}
