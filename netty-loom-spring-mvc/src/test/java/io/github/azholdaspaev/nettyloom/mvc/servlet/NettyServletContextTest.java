package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.github.azholdaspaev.nettyloom.mvc.filter.FilterRegistrationAdapter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettyServletContextTest {

    private NettyServletContext context;

    @BeforeEach
    void setUp() {
        context = new NettyServletContext("/api");
    }

    @Nested
    class ContextInfo {

        @Test
        void shouldReturnContextPath() {
            // Given - context with /api path

            // When
            String path = context.getContextPath();

            // Then
            assertThat(path).isEqualTo("/api");
        }

        @Test
        void shouldReturnEmptyContextPathWhenNull() {
            // Given
            context = new NettyServletContext(null);

            // When
            String path = context.getContextPath();

            // Then
            assertThat(path).isEmpty();
        }

        @Test
        void shouldReturnServerInfo() {
            // Given - context

            // When
            String info = context.getServerInfo();

            // Then
            assertThat(info).isEqualTo("Netty-Loom/1.0");
        }

        @Test
        void shouldReturnServletVersions() {
            // Given - context

            // When/Then
            assertThat(context.getMajorVersion()).isEqualTo(6);
            assertThat(context.getMinorVersion()).isEqualTo(0);
            assertThat(context.getEffectiveMajorVersion()).isEqualTo(6);
            assertThat(context.getEffectiveMinorVersion()).isEqualTo(0);
        }

        @Test
        void shouldReturnContextName() {
            // Given - context

            // When
            String name = context.getServletContextName();

            // Then
            assertThat(name).isEqualTo("Netty-Loom Servlet Context");
        }
    }

    @Nested
    class Attributes {

        @Test
        void shouldSetAndGetAttribute() {
            // Given
            Object value = new Object();

            // When
            context.setAttribute("myAttr", value);

            // Then
            assertThat(context.getAttribute("myAttr")).isSameAs(value);
        }

        @Test
        void shouldRemoveAttribute() {
            // Given
            context.setAttribute("myAttr", "value");

            // When
            context.removeAttribute("myAttr");

            // Then
            assertThat(context.getAttribute("myAttr")).isNull();
        }

        @Test
        void shouldRemoveAttributeWhenSetToNull() {
            // Given
            context.setAttribute("myAttr", "value");

            // When
            context.setAttribute("myAttr", null);

            // Then
            assertThat(context.getAttribute("myAttr")).isNull();
        }

        @Test
        void shouldReturnAttributeNames() {
            // Given
            context.setAttribute("attr1", "value1");
            context.setAttribute("attr2", "value2");

            // When
            Enumeration<String> names = context.getAttributeNames();

            // Then
            assertThat(Collections.list(names)).containsExactlyInAnyOrder("attr1", "attr2");
        }
    }

    @Nested
    class InitParameters {

        @Test
        void shouldSetAndGetInitParameter() {
            // Given

            // When
            boolean result = context.setInitParameter("param1", "value1");

            // Then
            assertThat(result).isTrue();
            assertThat(context.getInitParameter("param1")).isEqualTo("value1");
        }

        @Test
        void shouldReturnFalseWhenParameterExists() {
            // Given
            context.setInitParameter("param1", "value1");

            // When
            boolean result = context.setInitParameter("param1", "value2");

            // Then
            assertThat(result).isFalse();
            assertThat(context.getInitParameter("param1")).isEqualTo("value1");
        }

        @Test
        void shouldReturnInitParameterNames() {
            // Given
            context.setInitParameter("param1", "value1");
            context.setInitParameter("param2", "value2");

            // When
            Enumeration<String> names = context.getInitParameterNames();

            // Then
            assertThat(Collections.list(names)).containsExactlyInAnyOrder("param1", "param2");
        }
    }

    @Nested
    class ServletRegistrationTests {

        @Test
        void shouldRegisterServletInstance() {
            // Given
            Servlet servlet = new TestServlet();

            // When
            jakarta.servlet.ServletRegistration.Dynamic registration = context.addServlet("testServlet", servlet);

            // Then
            assertThat(registration).isNotNull();
            assertThat(registration.getName()).isEqualTo("testServlet");
            assertThat(context.getServlet("testServlet")).isSameAs(servlet);
        }

        @Test
        void shouldRegisterServletByClass() {
            // Given

            // When
            jakarta.servlet.ServletRegistration.Dynamic registration = context.addServlet("testServlet", TestServlet.class);

            // Then
            assertThat(registration).isNotNull();
            assertThat(context.getServlet("testServlet")).isInstanceOf(TestServlet.class);
        }

        @Test
        void shouldRetrieveServletRegistration() {
            // Given
            context.addServlet("testServlet", new TestServlet());

            // When
            jakarta.servlet.ServletRegistration registration = context.getServletRegistration("testServlet");

            // Then
            assertThat(registration).isNotNull();
            assertThat(registration.getName()).isEqualTo("testServlet");
        }

        @Test
        void shouldReturnAllServletRegistrations() {
            // Given
            context.addServlet("servlet1", new TestServlet());
            context.addServlet("servlet2", new TestServlet());

            // When
            Map<String, ? extends jakarta.servlet.ServletRegistration> registrations =
                    context.getServletRegistrations();

            // Then
            assertThat(registrations).hasSize(2);
            assertThat(registrations).containsKeys("servlet1", "servlet2");
        }

        @Test
        void shouldThrowForServletByClassName() {
            // Given - context

            // When/Then
            assertThatThrownBy(() -> context.addServlet("test", "com.example.TestServlet"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class FilterRegistrationTests {

        @Test
        void shouldRegisterFilterInstance() {
            // Given
            Filter filter = new TestFilter();

            // When
            FilterRegistration.Dynamic registration = context.addFilter("testFilter", filter);

            // Then
            assertThat(registration).isNotNull();
            assertThat(registration.getName()).isEqualTo("testFilter");
            assertThat(context.getFilter("testFilter")).isSameAs(filter);
        }

        @Test
        void shouldRegisterFilterByClass() {
            // Given

            // When
            FilterRegistration.Dynamic registration = context.addFilter("testFilter", TestFilter.class);

            // Then
            assertThat(registration).isNotNull();
            assertThat(context.getFilter("testFilter")).isInstanceOf(TestFilter.class);
        }

        @Test
        void shouldRetrieveFilterRegistration() {
            // Given
            context.addFilter("testFilter", new TestFilter());

            // When
            FilterRegistration registration = context.getFilterRegistration("testFilter");

            // Then
            assertThat(registration).isNotNull();
            assertThat(registration.getName()).isEqualTo("testFilter");
        }

        @Test
        void shouldReturnAllFilterRegistrations() {
            // Given
            context.addFilter("filter1", new TestFilter());
            context.addFilter("filter2", new TestFilter());

            // When
            Map<String, ? extends FilterRegistration> registrations = context.getFilterRegistrations();

            // Then
            assertThat(registrations).hasSize(2);
            assertThat(registrations).containsKeys("filter1", "filter2");
        }

        @Test
        void shouldThrowForFilterByClassName() {
            // Given - context

            // When/Then
            assertThatThrownBy(() -> context.addFilter("test", "com.example.TestFilter"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void shouldReturnFilterRegistrationAdapters() {
            // Given
            context.addFilter("filter1", new TestFilter());
            context.addFilter("filter2", new TestFilter());

            // When
            Collection<FilterRegistrationAdapter> adapters = context.getFilterRegistrationAdapters();

            // Then
            assertThat(adapters).hasSize(2);
        }
    }

    @Nested
    class MimeTypes {

        @Test
        void shouldReturnHtmlMimeType() {
            assertThat(context.getMimeType("index.html")).isEqualTo("text/html");
            assertThat(context.getMimeType("page.htm")).isEqualTo("text/html");
        }

        @Test
        void shouldReturnCssMimeType() {
            assertThat(context.getMimeType("style.css")).isEqualTo("text/css");
        }

        @Test
        void shouldReturnJsMimeType() {
            assertThat(context.getMimeType("app.js")).isEqualTo("application/javascript");
        }

        @Test
        void shouldReturnJsonMimeType() {
            assertThat(context.getMimeType("data.json")).isEqualTo("application/json");
        }

        @Test
        void shouldReturnDefaultMimeType() {
            assertThat(context.getMimeType("file.unknown")).isEqualTo("application/octet-stream");
        }

        @Test
        void shouldReturnNullForNullFile() {
            assertThat(context.getMimeType(null)).isNull();
        }
    }

    // Test implementations
    public static class TestServlet implements Servlet {
        @Override
        public void init(ServletConfig config) {}
        @Override
        public ServletConfig getServletConfig() { return null; }
        @Override
        public void service(ServletRequest req, ServletResponse res) {}
        @Override
        public String getServletInfo() { return "Test Servlet"; }
        @Override
        public void destroy() {}
    }

    public static class TestFilter implements Filter {
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
