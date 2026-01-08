package io.github.azholdaspaev.nettyloom.mvc.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class FilterChainAdapterTest {

    private ServletRequest request;
    private ServletResponse response;
    private List<String> executionOrder;

    @BeforeEach
    void setUp() {
        request = mock(ServletRequest.class);
        response = mock(ServletResponse.class);
        executionOrder = new ArrayList<>();
    }

    @Nested
    class BasicChainExecution {

        @Test
        void shouldExecuteFiltersInOrder() throws Exception {
            // Given
            Filter filter1 = createTrackingFilter("filter1");
            Filter filter2 = createTrackingFilter("filter2");
            Servlet servlet = createTrackingServlet("servlet");

            FilterChainAdapter chain = new FilterChainAdapter(
                    new Filter[]{filter1, filter2},
                    servlet
            );

            // When
            chain.doFilter(request, response);

            // Then
            assertThat(executionOrder).containsExactly(
                    "filter1-before",
                    "filter2-before",
                    "servlet",
                    "filter2-after",
                    "filter1-after"
            );
        }

        @Test
        void shouldInvokeServletDirectlyWithNoFilters() throws Exception {
            // Given
            Servlet servlet = createTrackingServlet("servlet");
            FilterChainAdapter chain = new FilterChainAdapter(servlet);

            // When
            chain.doFilter(request, response);

            // Then
            assertThat(executionOrder).containsExactly("servlet");
        }

        @Test
        void shouldInvokeServletAfterEmptyFilterArray() throws Exception {
            // Given
            Servlet servlet = createTrackingServlet("servlet");
            FilterChainAdapter chain = new FilterChainAdapter(new Filter[0], servlet);

            // When
            chain.doFilter(request, response);

            // Then
            assertThat(executionOrder).containsExactly("servlet");
        }

        @Test
        void shouldHandleNullFilterArray() throws Exception {
            // Given
            Servlet servlet = createTrackingServlet("servlet");
            FilterChainAdapter chain = new FilterChainAdapter((Filter[]) null, servlet);

            // When
            chain.doFilter(request, response);

            // Then
            assertThat(executionOrder).containsExactly("servlet");
        }
    }

    @Nested
    class FilterShortCircuit {

        @Test
        void shouldAllowFilterToShortCircuit() throws Exception {
            // Given
            Filter filter1 = createTrackingFilter("filter1");
            Filter shortCircuitFilter = createShortCircuitFilter("shortCircuit");
            Filter filter3 = createTrackingFilter("filter3");
            Servlet servlet = createTrackingServlet("servlet");

            FilterChainAdapter chain = new FilterChainAdapter(
                    new Filter[]{filter1, shortCircuitFilter, filter3},
                    servlet
            );

            // When
            chain.doFilter(request, response);

            // Then
            assertThat(executionOrder).containsExactly(
                    "filter1-before",
                    "shortCircuit-blocked",
                    "filter1-after"
            );
            // filter3 and servlet should NOT be called
        }
    }

    @Nested
    class ExceptionHandling {

        @Test
        void shouldPropagateFilterException() {
            // Given
            Filter throwingFilter = createThrowingFilter();
            Servlet servlet = createTrackingServlet("servlet");

            FilterChainAdapter chain = new FilterChainAdapter(
                    new Filter[]{throwingFilter},
                    servlet
            );

            // When/Then
            assertThatThrownBy(() -> chain.doFilter(request, response))
                    .isInstanceOf(ServletException.class)
                    .hasMessage("Filter exception");
        }

        @Test
        void shouldPropagateServletException() {
            // Given
            Servlet throwingServlet = createThrowingServlet();
            FilterChainAdapter chain = new FilterChainAdapter(throwingServlet);

            // When/Then
            assertThatThrownBy(() -> chain.doFilter(request, response))
                    .isInstanceOf(ServletException.class)
                    .hasMessage("Servlet exception");
        }
    }

    @Nested
    class ChainState {

        @Test
        void shouldTrackCurrentIndex() throws Exception {
            // Given
            Filter filter1 = createTrackingFilter("filter1");
            Filter filter2 = createTrackingFilter("filter2");
            Servlet servlet = createTrackingServlet("servlet");

            FilterChainAdapter chain = new FilterChainAdapter(
                    new Filter[]{filter1, filter2},
                    servlet
            );

            // When
            assertThat(chain.getCurrentIndex()).isEqualTo(0);
            chain.doFilter(request, response);

            // Then
            assertThat(chain.getCurrentIndex()).isEqualTo(2);
        }

        @Test
        void shouldReturnFilterCount() {
            // Given
            Filter[] filters = new Filter[]{
                    createTrackingFilter("f1"),
                    createTrackingFilter("f2"),
                    createTrackingFilter("f3")
            };

            FilterChainAdapter chain = new FilterChainAdapter(filters, null);

            // When/Then
            assertThat(chain.getFilterCount()).isEqualTo(3);
        }

        @Test
        void shouldReturnTerminalServlet() {
            // Given
            Servlet servlet = createTrackingServlet("servlet");
            FilterChainAdapter chain = new FilterChainAdapter(servlet);

            // When/Then
            assertThat(chain.getTerminalServlet()).isSameAs(servlet);
        }

        @Test
        void shouldResetChain() throws Exception {
            // Given
            Filter filter = createTrackingFilter("filter");
            Servlet servlet = createTrackingServlet("servlet");
            FilterChainAdapter chain = new FilterChainAdapter(new Filter[]{filter}, servlet);

            // First execution
            chain.doFilter(request, response);
            assertThat(chain.getCurrentIndex()).isEqualTo(1);

            // When
            chain.reset();

            // Then
            assertThat(chain.getCurrentIndex()).isEqualTo(0);
        }
    }

    @Nested
    class ListConstructor {

        @Test
        void shouldAcceptFilterList() throws Exception {
            // Given
            List<Filter> filters = List.of(
                    createTrackingFilter("filter1"),
                    createTrackingFilter("filter2")
            );
            Servlet servlet = createTrackingServlet("servlet");

            FilterChainAdapter chain = new FilterChainAdapter(filters, servlet);

            // When
            chain.doFilter(request, response);

            // Then
            assertThat(executionOrder).containsExactly(
                    "filter1-before",
                    "filter2-before",
                    "servlet",
                    "filter2-after",
                    "filter1-after"
            );
        }

        @Test
        void shouldHandleNullList() throws Exception {
            // Given
            Servlet servlet = createTrackingServlet("servlet");
            FilterChainAdapter chain = new FilterChainAdapter((List<Filter>) null, servlet);

            // When
            chain.doFilter(request, response);

            // Then
            assertThat(executionOrder).containsExactly("servlet");
        }
    }

    @Nested
    class NullServlet {

        @Test
        void shouldHandleNullTerminalServlet() throws Exception {
            // Given
            Filter filter = createTrackingFilter("filter");
            FilterChainAdapter chain = new FilterChainAdapter(new Filter[]{filter}, null);

            // When
            chain.doFilter(request, response);

            // Then
            assertThat(executionOrder).containsExactly("filter-before", "filter-after");
            // No servlet call, but no exception either
        }
    }

    // Helper methods to create test filters and servlets

    private Filter createTrackingFilter(String name) {
        return new Filter() {
            @Override
            public void init(FilterConfig filterConfig) {}

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                executionOrder.add(name + "-before");
                chain.doFilter(request, response);
                executionOrder.add(name + "-after");
            }

            @Override
            public void destroy() {}
        };
    }

    private Filter createShortCircuitFilter(String name) {
        return new Filter() {
            @Override
            public void init(FilterConfig filterConfig) {}

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
                executionOrder.add(name + "-blocked");
                // Do NOT call chain.doFilter() - short-circuit the chain
            }

            @Override
            public void destroy() {}
        };
    }

    private Filter createThrowingFilter() {
        return new Filter() {
            @Override
            public void init(FilterConfig filterConfig) {}

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws ServletException {
                throw new ServletException("Filter exception");
            }

            @Override
            public void destroy() {}
        };
    }

    private Servlet createTrackingServlet(String name) {
        return new Servlet() {
            @Override
            public void init(ServletConfig config) {}

            @Override
            public ServletConfig getServletConfig() { return null; }

            @Override
            public void service(ServletRequest req, ServletResponse res) {
                executionOrder.add(name);
            }

            @Override
            public String getServletInfo() { return name; }

            @Override
            public void destroy() {}
        };
    }

    private Servlet createThrowingServlet() {
        return new Servlet() {
            @Override
            public void init(ServletConfig config) {}

            @Override
            public ServletConfig getServletConfig() { return null; }

            @Override
            public void service(ServletRequest req, ServletResponse res) throws ServletException {
                throw new ServletException("Servlet exception");
            }

            @Override
            public String getServletInfo() { return "Throwing Servlet"; }

            @Override
            public void destroy() {}
        };
    }
}
