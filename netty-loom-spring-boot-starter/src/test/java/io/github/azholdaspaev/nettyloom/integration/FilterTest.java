package io.github.azholdaspaev.nettyloom.integration;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Servlet Filter execution:
 * - Filter execution before controller
 * - Filter chain progression
 * - Filter registration order
 * - URL pattern matching
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = FilterTest.TestApplication.class
)
class FilterTest extends BaseIntegrationTest {

    @Nested
    class FilterExecution {

        @Test
        void shouldExecuteFilterBeforeController() {
            // Given - logging filter is registered

            // When
            ResponseEntity<String> response = get("/filter/simple/hello", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("X-Filter-Executed")).isEqualTo("true");
        }

        @Test
        void shouldPassRequestResponseToNextFilter() {
            // Given - chain filter that modifies request attribute

            // When
            ResponseEntity<String> response = get("/filter/chain/data", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("filter-value");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldExecuteFiltersInRegistrationOrder() {
            // Given - multiple filters with specific order

            // When
            ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>)
                    (ResponseEntity<?>) get("/filter/order/sequence", Map.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<String> filterOrder = (List<String>) response.getBody().get("filterOrder");
            assertThat(filterOrder).containsExactly("first", "second", "third", "controller");
        }

        @Test
        void shouldBlockRequestWhenFilterDoesNotCallChain() {
            // Given - blocking filter that doesn't call chain.doFilter()

            // When
            ResponseEntity<String> response = get("/filter/blocked/resource", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("Blocked by filter");
        }
    }

    @Nested
    class FilterConfiguration {

        @Test
        void shouldApplyFilterToMatchingUrlPatterns() {
            // Given - api filter registered for /filter/api/*

            // When
            ResponseEntity<String> response = get("/filter/api/resource", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("X-Api-Filter")).isEqualTo("applied");
        }

        @Test
        void shouldSkipFilterForNonMatchingPaths() {
            // Given - api filter registered for /filter/api/* only

            // When
            ResponseEntity<String> response = get("/filter/other/resource", String.class);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("X-Api-Filter")).isNull();
        }
    }

    @SpringBootApplication
    @ComponentScan(excludeFilters = @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ".*(?:AutoConfigurationTest|IntegrationTest|MvcAnnotationsTest|ExceptionHandlingTest|InterceptorTest).*"
    ))
    static class TestApplication {

        // Shared list for tracking filter execution order
        private static final List<String> filterOrder = Collections.synchronizedList(new ArrayList<>());

        public static List<String> getFilterOrder() {
            return filterOrder;
        }

        public static void clearFilterOrder() {
            filterOrder.clear();
        }

        /**
         * Simple filter that adds a header
         */
        @Bean
        public FilterRegistrationBean<Filter> simpleFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setFilter((request, response, chain) -> {
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                httpResponse.setHeader("X-Filter-Executed", "true");
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/filter/simple/*");
            registration.setOrder(1);
            return registration;
        }

        /**
         * Filter that passes data to the controller via request attribute
         */
        @Bean
        public FilterRegistrationBean<Filter> chainDataFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setFilter((request, response, chain) -> {
                request.setAttribute("filterData", "filter-value");
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/filter/chain/*");
            registration.setOrder(1);
            return registration;
        }

        /**
         * First order filter
         */
        @Bean
        public FilterRegistrationBean<Filter> firstOrderFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new Filter() {
                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                        throws IOException, ServletException {
                    clearFilterOrder();
                    getFilterOrder().add("first");
                    chain.doFilter(request, response);
                }
            });
            registration.addUrlPatterns("/filter/order/*");
            registration.setOrder(1);
            return registration;
        }

        /**
         * Second order filter
         */
        @Bean
        public FilterRegistrationBean<Filter> secondOrderFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setFilter((request, response, chain) -> {
                getFilterOrder().add("second");
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/filter/order/*");
            registration.setOrder(2);
            return registration;
        }

        /**
         * Third order filter
         */
        @Bean
        public FilterRegistrationBean<Filter> thirdOrderFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setFilter((request, response, chain) -> {
                getFilterOrder().add("third");
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/filter/order/*");
            registration.setOrder(3);
            return registration;
        }

        /**
         * Blocking filter that doesn't call chain.doFilter()
         */
        @Bean
        public FilterRegistrationBean<Filter> blockingFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setFilter((request, response, chain) -> {
                HttpServletRequest httpRequest = (HttpServletRequest) request;
                HttpServletResponse httpResponse = (HttpServletResponse) response;

                // Check for auth header (just for testing)
                String authHeader = httpRequest.getHeader("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    httpResponse.getWriter().write("Blocked by filter: Missing authorization");
                    // Don't call chain.doFilter() - request is blocked
                    return;
                }
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/filter/blocked/*");
            registration.setOrder(1);
            return registration;
        }

        /**
         * API filter that only applies to /filter/api/* paths
         */
        @Bean
        public FilterRegistrationBean<Filter> apiFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setFilter((request, response, chain) -> {
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                httpResponse.setHeader("X-Api-Filter", "applied");
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/filter/api/*");
            registration.setOrder(1);
            return registration;
        }

        @RestController
        static class SimpleController {

            @GetMapping("/filter/simple/hello")
            public String hello() {
                return "Hello from filtered endpoint";
            }
        }

        @RestController
        static class ChainController {

            @GetMapping("/filter/chain/data")
            public String data(HttpServletRequest request) {
                Object filterData = request.getAttribute("filterData");
                return "Data from filter: " + (filterData != null ? filterData : "none");
            }
        }

        @RestController
        static class OrderController {

            @GetMapping("/filter/order/sequence")
            public Map<String, Object> sequence() {
                getFilterOrder().add("controller");
                return Map.of("filterOrder", new ArrayList<>(getFilterOrder()));
            }
        }

        @RestController
        static class BlockedController {

            @GetMapping("/filter/blocked/resource")
            public String resource() {
                return "This should not be reached without auth";
            }
        }

        @RestController
        static class ApiController {

            @GetMapping("/filter/api/resource")
            public String apiResource() {
                return "API resource";
            }

            @GetMapping("/filter/other/resource")
            public String otherResource() {
                return "Other resource (no API filter)";
            }
        }
    }
}
