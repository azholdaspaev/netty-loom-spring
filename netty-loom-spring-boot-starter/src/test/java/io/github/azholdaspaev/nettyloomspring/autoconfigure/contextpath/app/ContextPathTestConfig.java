package io.github.azholdaspaev.nettyloomspring.autoconfigure.contextpath.app;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class ContextPathTestConfig {

    // Mapped by the context-relative pattern "/hello" (the servlet path), proving filter matching
    // uses the in-context path rather than the full request URI once a context path is set.
    @Bean
    FilterRegistrationBean<Filter> contextPathFilter() {
        var registration = new FilterRegistrationBean<Filter>(new HeaderFilter());
        registration.addUrlPatterns("/hello");
        return registration;
    }

    private static final class HeaderFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            ((HttpServletResponse) response).setHeader("X-Ctx-Filter", "yes");
            chain.doFilter(request, response);
        }
    }
}
