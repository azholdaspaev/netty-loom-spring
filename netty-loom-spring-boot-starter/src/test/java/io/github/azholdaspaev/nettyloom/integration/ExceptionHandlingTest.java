package io.github.azholdaspaev.nettyloom.integration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Spring MVC exception handling:
 * - @ExceptionHandler at controller level
 * - @ControllerAdvice for global exception handling
 * - Custom exception responses
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ExceptionHandlingTest.TestApplication.class
)
class ExceptionHandlingTest extends BaseIntegrationTest {

    @Nested
    class ControllerLevelExceptionHandler {

        @Test
        void shouldCatchExceptionInController() {
            // Given
            String id = "invalid";

            // When
            ResponseEntity<String> response = get("/exception/local/parse/" + id, String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("Invalid number format");
        }

        @Test
        void shouldReturnCustomErrorResponse() {
            // Given - triggering controller-level exception handler

            // When
            ResponseEntity<String> response = get("/exception/local/parse/abc", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo("Invalid number format: abc");
        }
    }

    @Nested
    class GlobalControllerAdvice {

        @Test
        void shouldCatchExceptionFromAnyController() {
            // Given - exception from controller without local handler

            // When
            ResponseEntity<String> response = get("/exception/global/notfound/123", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).contains("Resource not found");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldHandleMultipleExceptionTypes() {
            // Given - different exception type

            // When
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) get("/exception/global/validation", Map.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody())
                    .containsKey("error")
                    .containsKey("message");
        }

        @Test
        void shouldReturnProperHttpStatus() {
            // Given - exception that maps to specific status

            // When
            ResponseEntity<String> response = get("/exception/global/forbidden", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    class ExceptionPropagation {

        @Test
        void shouldNotLeakStackTraceByDefault() {
            // Given - internal exception

            // When
            ResponseEntity<String> response = get("/exception/global/internal", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).doesNotContain("at io.github");
            assertThat(response.getBody()).doesNotContain(".java:");
        }

        @Test
        void shouldReturnCustomErrorBody() {
            // Given - exception with custom body

            // When
            ResponseEntity<String> response = get("/exception/global/notfound/456", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isEqualTo("Resource not found: 456");
        }
    }

    @SpringBootApplication
    @ComponentScan(excludeFilters = @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ".*(?:AutoConfigurationTest|IntegrationTest|MvcAnnotationsTest).*"
    ))
    static class TestApplication {

        // Custom exceptions
        static class ResourceNotFoundException extends RuntimeException {
            public ResourceNotFoundException(String message) {
                super(message);
            }
        }

        static class ValidationException extends RuntimeException {
            public ValidationException(String message) {
                super(message);
            }
        }

        static class ForbiddenException extends RuntimeException {
            public ForbiddenException(String message) {
                super(message);
            }
        }

        /**
         * Controller with local @ExceptionHandler
         */
        @RestController
        static class LocalExceptionController {

            @GetMapping("/exception/local/parse/{value}")
            public String parseNumber(@PathVariable String value) {
                Integer.parseInt(value); // Will throw NumberFormatException for non-numeric
                return "Parsed: " + value;
            }

            @ExceptionHandler(NumberFormatException.class)
            @ResponseStatus(HttpStatus.BAD_REQUEST)
            @ResponseBody
            public String handleNumberFormat(NumberFormatException ex) {
                return "Invalid number format: " + ex.getMessage().replace("For input string: ", "").replace("\"", "");
            }
        }

        /**
         * Controller without local exception handlers (relies on global advice)
         */
        @RestController
        static class GlobalExceptionController {

            @GetMapping("/exception/global/notfound/{id}")
            public String findResource(@PathVariable String id) {
                throw new ResourceNotFoundException(id);
            }

            @GetMapping("/exception/global/validation")
            public String validate() {
                throw new ValidationException("Field 'email' is invalid");
            }

            @GetMapping("/exception/global/forbidden")
            public String forbidden() {
                throw new ForbiddenException("Access denied");
            }

            @GetMapping("/exception/global/internal")
            public String internal() {
                throw new RuntimeException("Unexpected internal error");
            }
        }

        /**
         * Global exception handler using @ControllerAdvice
         */
        @ControllerAdvice
        static class GlobalExceptionHandler {

            @ExceptionHandler(ResourceNotFoundException.class)
            @ResponseStatus(HttpStatus.NOT_FOUND)
            @ResponseBody
            public String handleNotFound(ResourceNotFoundException ex) {
                return "Resource not found: " + ex.getMessage();
            }

            @ExceptionHandler(ValidationException.class)
            @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
            @ResponseBody
            public Map<String, String> handleValidation(ValidationException ex) {
                return Map.of(
                        "error", "Validation Error",
                        "message", ex.getMessage()
                );
            }

            @ExceptionHandler(ForbiddenException.class)
            @ResponseStatus(HttpStatus.FORBIDDEN)
            @ResponseBody
            public String handleForbidden(ForbiddenException ex) {
                return "Forbidden: " + ex.getMessage();
            }

            @ExceptionHandler(RuntimeException.class)
            @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
            @ResponseBody
            public String handleInternal(RuntimeException ex) {
                // Don't expose internal details
                return "Internal server error";
            }
        }
    }
}
