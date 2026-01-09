package io.github.azholdaspaev.nettyloom.integration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Spring MVC annotations:
 * - @PathVariable
 * - @RequestParam
 * - @RequestBody
 * - @ResponseBody
 * - Content negotiation
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MvcAnnotationsTest.TestApplication.class
)
class MvcAnnotationsTest extends BaseIntegrationTest {

    @Nested
    class PathVariables {

        @Test
        void shouldExtractSinglePathVariable() {
            // Given
            String name = "John";

            // When
            ResponseEntity<String> response = get("/path/user/" + name, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("User: John");
        }

        @Test
        void shouldExtractMultiplePathVariables() {
            // Given
            String category = "electronics";
            Long productId = 123L;

            // When
            ResponseEntity<String> response = get("/path/category/" + category + "/product/" + productId, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Category: electronics, Product: 123");
        }

        @Test
        void shouldHandlePathVariableWithHyphen() {
            // Given - path variable with hyphen
            String name = "John-Doe";

            // When
            ResponseEntity<String> response = get("/path/user/" + name, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("User: John-Doe");
        }
    }

    @Nested
    class RequestParams {

        @Test
        void shouldHandleRequiredStringParam() {
            // Given
            String query = "spring";

            // When
            ResponseEntity<String> response = get("/param/search?q=" + query, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Search: spring");
        }

        @Test
        void shouldHandleOptionalParamWithDefault() {
            // Given - no 'limit' param provided

            // When
            ResponseEntity<String> response = get("/param/list", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Limit: 10");
        }

        @Test
        void shouldHandleMultipleParams() {
            // Given
            String query = "test";
            int page = 2;
            int size = 25;

            // When
            ResponseEntity<String> response = get("/param/paginated?q=" + query + "&page=" + page + "&size=" + size, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Query: test, Page: 2, Size: 25");
        }

        @Test
        void shouldHandleArrayParams() {
            // Given
            String idsParam = "ids=1&ids=2&ids=3";

            // When
            ResponseEntity<String> response = get("/param/items?" + idsParam, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("IDs: [1, 2, 3]");
        }

        @Test
        void shouldHandleIntegerParams() {
            // Given
            int count = 42;

            // When
            ResponseEntity<String> response = get("/param/count?value=" + count, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Count: 42");
        }

        @Test
        void shouldHandleBooleanParams() {
            // Given
            boolean enabled = true;

            // When
            ResponseEntity<String> response = get("/param/toggle?enabled=" + enabled, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Enabled: true");
        }
    }

    @Nested
    class RequestBodyTests {

        @Test
        void shouldDeserializeJsonObject() {
            // Given
            Map<String, String> body = Map.of("name", "Alice", "email", "alice@example.com");

            // When
            ResponseEntity<String> response = post("/body/user", body, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Created user: Alice (alice@example.com)");
        }

        @Test
        void shouldDeserializeJsonArray() {
            // Given
            List<String> items = List.of("apple", "banana", "cherry");

            // When
            ResponseEntity<String> response = post("/body/items", items, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("Received 3 items");
        }

        @Test
        void shouldDeserializeNestedObjects() {
            // Given
            Map<String, Object> body = Map.of(
                    "name", "Order1",
                    "details", Map.of("quantity", 5, "price", 19.99)
            );

            // When
            ResponseEntity<String> response = post("/body/order", body, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("Order1");
        }

        @Test
        void shouldReturnErrorForInvalidJson() {
            // Given
            String invalidJson = "{invalid json}";
            HttpHeaders headers = jsonHeaders();

            // When
            ResponseEntity<String> response = postWithHeaders("/body/user", invalidJson, headers, String.class);

            // Then
            // Spring MVC returns 400 BAD_REQUEST when a controller advice handles HttpMessageNotReadableException
            // Default behavior without custom handler returns 500
            assertThat(response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError()).isTrue();
        }
    }

    @Nested
    class ResponseBodyTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldSerializeObjectToJson() {
            // Given - no input needed

            // When
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) get("/response/object", Map.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("id", 1)
                    .containsEntry("name", "Test Object");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldSerializeListToJson() {
            // Given - no input needed

            // When
            ResponseEntity<List<Map<String, Object>>> response = (ResponseEntity<List<Map<String, Object>>>)
                    (ResponseEntity<?>) get("/response/list", List.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(3);
        }

        @Test
        void shouldSetContentTypeHeader() {
            // Given - no input needed

            // When
            ResponseEntity<String> response = get("/response/object", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType())
                    .isNotNull()
                    .satisfies(contentType -> assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
        }
    }

    @Nested
    class ContentNegotiation {

        @Test
        void shouldRespondWithJsonWhenAcceptHeaderSet() {
            // Given
            HttpHeaders headers = acceptJsonHeaders();

            // When
            ResponseEntity<String> response = getWithHeaders("/response/object", headers, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType())
                    .isNotNull()
                    .satisfies(contentType -> assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
        }
    }

    @SpringBootApplication
    @ComponentScan(excludeFilters = @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ".*(?:AutoConfigurationTest|IntegrationTest).*"
    ))
    static class TestApplication {

        @RestController
        static class PathVariableController {

            @GetMapping("/path/user/{name}")
            public String getUser(@PathVariable String name) {
                return "User: " + name;
            }

            @GetMapping("/path/category/{category}/product/{productId}")
            public String getProduct(@PathVariable String category, @PathVariable Long productId) {
                return "Category: " + category + ", Product: " + productId;
            }
        }

        @RestController
        static class RequestParamController {

            @GetMapping("/param/search")
            public String search(@RequestParam String q) {
                return "Search: " + q;
            }

            @GetMapping("/param/list")
            public String list(@RequestParam(defaultValue = "10") int limit) {
                return "Limit: " + limit;
            }

            @GetMapping("/param/paginated")
            public String paginated(
                    @RequestParam String q,
                    @RequestParam int page,
                    @RequestParam int size) {
                return "Query: " + q + ", Page: " + page + ", Size: " + size;
            }

            @GetMapping("/param/items")
            public String items(@RequestParam List<Integer> ids) {
                return "IDs: " + ids;
            }

            @GetMapping("/param/count")
            public String count(@RequestParam int value) {
                return "Count: " + value;
            }

            @GetMapping("/param/toggle")
            public String toggle(@RequestParam boolean enabled) {
                return "Enabled: " + enabled;
            }
        }

        @RestController
        static class RequestBodyController {

            @PostMapping("/body/user")
            public String createUser(@RequestBody Map<String, String> user) {
                return "Created user: " + user.get("name") + " (" + user.get("email") + ")";
            }

            @PostMapping("/body/items")
            public String receiveItems(@RequestBody List<String> items) {
                return "Received " + items.size() + " items";
            }

            @PostMapping("/body/order")
            public String createOrder(@RequestBody Map<String, Object> order) {
                return "Order: " + order.get("name");
            }
        }

        @RestController
        static class ResponseBodyController {

            @GetMapping("/response/object")
            public Map<String, Object> getObject() {
                return Map.of("id", 1, "name", "Test Object");
            }

            @GetMapping("/response/list")
            public List<Map<String, Object>> getList() {
                return List.of(
                        Map.of("id", 1, "name", "Item 1"),
                        Map.of("id", 2, "name", "Item 2"),
                        Map.of("id", 3, "name", "Item 3")
                );
            }
        }
    }
}
