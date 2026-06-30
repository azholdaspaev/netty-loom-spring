package io.github.azholdaspaev.nettyloomspring.autoconfigure.filter;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestFixtures.HeaderFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@AutoConfigureRestTestClient
@SpringBootTest(
    classes = FilterTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class FilterChainIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private HeaderFilter headerFilter;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void runsFilterThatSetsResponseHeader() {
        restTestClient.get().uri("/api/greeting")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Filtered", "yes");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shortCircuitingFilterReturns403AndControllerIsNotInvoked() {
        restTestClient.get().uri("/secure/data")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody().isEmpty();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void filterRunsOnlyOnMatchingUrlPattern() {
        restTestClient.get().uri("/filtered/data")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Scoped", "yes");

        restTestClient.get().uri("/api/greeting")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().doesNotExist("X-Scoped");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void exactPatternMatchesAgainstQueryStrippedPath() {
        // /api/greeting is an EXACT filter mapping; a query string must not defeat the match,
        // proving the dispatcher matches on getRequestURI() (path only), not the raw URI.
        restTestClient.get().uri("/api/greeting?msg=hi")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Exact", "yes");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void filtersRunInOrderResolvedByOrderAnnotation() {
        restTestClient.get().uri("/api/greeting")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Order", "10", "20", "30");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void midChainExceptionIsCaughtByUpstreamErrorFilter() {
        restTestClient.get().uri("/boom/data")
            .exchange()
            .expectStatus().is5xxServerError()
            .expectHeader().valueEquals("X-Error-Handled", "yes");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void headerFilterIsInitialisedExactlyOnce() {
        assertEquals(1, headerFilter.initCount());
    }
}
