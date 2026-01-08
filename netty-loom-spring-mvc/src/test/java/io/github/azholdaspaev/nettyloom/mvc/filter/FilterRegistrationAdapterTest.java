package io.github.azholdaspaev.nettyloom.mvc.filter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class FilterRegistrationAdapterTest {

    private FilterRegistrationAdapter registration;
    private Filter testFilter;

    @BeforeEach
    void setUp() {
        testFilter = new TestFilter();
        registration = new FilterRegistrationAdapter("testFilter", testFilter);
    }

    @Nested
    class BasicProperties {

        @Test
        void shouldReturnFilterName() {
            assertThat(registration.getName()).isEqualTo("testFilter");
        }

        @Test
        void shouldReturnClassName() {
            assertThat(registration.getClassName()).isEqualTo(TestFilter.class.getName());
        }

        @Test
        void shouldReturnFilterInstance() {
            assertThat(registration.getFilter()).isSameAs(testFilter);
        }
    }

    @Nested
    class UrlPatternMatching {

        @Test
        void shouldMatchAllPathsWithWildcard() {
            // Given
            registration.addMappingForUrlPatterns(null, true, "/*");

            // When/Then
            assertThat(registration.matches("/anything")).isTrue();
            assertThat(registration.matches("/api/users")).isTrue();
            assertThat(registration.matches("/")).isTrue();
        }

        @Test
        void shouldMatchRootPath() {
            // Given
            registration.addMappingForUrlPatterns(null, true, "/");

            // When/Then
            assertThat(registration.matches("/")).isTrue();
            assertThat(registration.matches("/anything")).isTrue();
        }

        @Test
        void shouldMatchPathPrefix() {
            // Given
            registration.addMappingForUrlPatterns(null, true, "/api/*");

            // When/Then
            assertThat(registration.matches("/api/users")).isTrue();
            assertThat(registration.matches("/api")).isTrue();
            assertThat(registration.matches("/api/")).isTrue();
            assertThat(registration.matches("/other/path")).isFalse();
        }

        @Test
        void shouldMatchFileExtension() {
            // Given
            registration.addMappingForUrlPatterns(null, true, "*.json");

            // When/Then
            assertThat(registration.matches("/api/data.json")).isTrue();
            assertThat(registration.matches("/data.json")).isTrue();
            assertThat(registration.matches("/api/data.xml")).isFalse();
        }

        @Test
        void shouldMatchExactPath() {
            // Given
            registration.addMappingForUrlPatterns(null, true, "/api/users");

            // When/Then
            assertThat(registration.matches("/api/users")).isTrue();
            assertThat(registration.matches("/api/users/1")).isFalse();
            assertThat(registration.matches("/api")).isFalse();
        }

        @Test
        void shouldMatchMultiplePatterns() {
            // Given
            registration.addMappingForUrlPatterns(null, true, "/api/*", "*.json");

            // When/Then
            assertThat(registration.matches("/api/users")).isTrue();
            assertThat(registration.matches("/data.json")).isTrue();
            assertThat(registration.matches("/other")).isFalse();
        }
    }

    @Nested
    class DefaultMatchingBehavior {

        @Test
        void shouldMatchAllPathsWhenNoMappingsConfigured() {
            // Given - no URL patterns or servlet name mappings

            // When/Then
            assertThat(registration.matches("/any/path")).isTrue();
            assertThat(registration.matches("/")).isTrue();
            assertThat(registration.matches("/api/users")).isTrue();
        }

        @Test
        void shouldNotMatchWithMatchesUrlWhenNoPatterns() {
            // Given - no URL patterns

            // When/Then
            assertThat(registration.matchesUrl("/any/path")).isFalse();
        }

        @Test
        void shouldNotMatchAllWhenOnlyServletNameMappingExists() {
            // Given
            registration.addMappingForServletNames(null, true, "dispatcherServlet");

            // When/Then - has servlet mapping but not URL mapping
            assertThat(registration.matches("/any/path")).isFalse();
        }
    }

    @Nested
    class UrlPatternMappingOrder {

        @Test
        void shouldAddMappingsAtEnd() {
            // Given
            registration.addMappingForUrlPatterns(null, true, "/first");
            registration.addMappingForUrlPatterns(null, true, "/second");

            // When
            var mappings = registration.getUrlPatternMappings();

            // Then
            assertThat(mappings).containsExactly("/first", "/second");
        }

        @Test
        void shouldAddMappingsAtBeginning() {
            // Given
            registration.addMappingForUrlPatterns(null, true, "/first");
            registration.addMappingForUrlPatterns(null, false, "/second");

            // When
            var mappings = registration.getUrlPatternMappings();

            // Then
            assertThat(mappings).containsExactly("/second", "/first");
        }
    }

    @Nested
    class ServletNameMatching {

        @Test
        void shouldMatchServletName() {
            // Given
            registration.addMappingForServletNames(null, true, "dispatcherServlet");

            // When/Then
            assertThat(registration.matchesServletName("dispatcherServlet")).isTrue();
            assertThat(registration.matchesServletName("otherServlet")).isFalse();
        }
    }

    @Nested
    class InitParameters {

        @Test
        void shouldSetAndGetInitParameter() {
            // Given
            boolean result = registration.setInitParameter("key", "value");

            // Then
            assertThat(result).isTrue();
            assertThat(registration.getInitParameter("key")).isEqualTo("value");
        }

        @Test
        void shouldNotOverwriteExistingParameter() {
            // Given
            registration.setInitParameter("key", "value1");

            // When
            boolean result = registration.setInitParameter("key", "value2");

            // Then
            assertThat(result).isFalse();
            assertThat(registration.getInitParameter("key")).isEqualTo("value1");
        }
    }

    @Nested
    class AsyncSupport {

        @Test
        void shouldDefaultToAsyncNotSupported() {
            assertThat(registration.isAsyncSupported()).isFalse();
        }

        @Test
        void shouldSetAsyncSupported() {
            // When
            registration.setAsyncSupported(true);

            // Then
            assertThat(registration.isAsyncSupported()).isTrue();
        }
    }

    @Nested
    class DispatcherTypes {

        @Test
        void shouldDefaultToRequestDispatcherType() {
            assertThat(registration.getDispatcherTypes())
                    .containsExactly(DispatcherType.REQUEST);
        }

        @Test
        void shouldUpdateDispatcherTypes() {
            // When
            registration.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD),
                    true,
                    "/*"
            );

            // Then
            assertThat(registration.getDispatcherTypes())
                    .containsExactlyInAnyOrder(DispatcherType.REQUEST, DispatcherType.FORWARD);
        }
    }

    // Test filter implementation
    private static class TestFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) {}

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {}
    }
}
