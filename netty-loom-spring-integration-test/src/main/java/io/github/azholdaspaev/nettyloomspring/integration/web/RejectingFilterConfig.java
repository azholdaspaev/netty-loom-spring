package io.github.azholdaspaev.nettyloomspring.integration.web;

import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RejectingFilterConfig {

    public static final String REJECTED_PATH = "/items/rejected";

    @Bean
    public FilterRegistrationBean<Filter> rejectingFilter() {
        Filter filter = (_, _, _) -> {
            throw new IllegalArgumentException("Rejected before reaching the controller");
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(REJECTED_PATH);
        return registration;
    }
}
