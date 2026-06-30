package io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestFixtures.ErrorHandlingFilter;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestFixtures.ForbiddenFilter;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestFixtures.HeaderFilter;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestFixtures.OrderFilter;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestFixtures.SetHeaderFilter;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.filter.app.FilterTestFixtures.ThrowingFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterTestConfig {

    @Bean
    HeaderFilter headerFilter() {
        return new HeaderFilter();
    }

    @Bean
    FilterRegistrationBean<HeaderFilter> headerFilterRegistration(HeaderFilter headerFilter) {
        return registration(headerFilter, 0, "/*");
    }

    // Declared deliberately OUT of @Order sequence (30, 10, 20) so the ordering test can only
    // pass if @Order resolution actually sorts them — not by accident of bean declaration order.
    @Bean
    FilterRegistrationBean<OrderFilter> orderFilter30() {
        return registration(new OrderFilter("30"), 30, "/*");
    }

    @Bean
    FilterRegistrationBean<OrderFilter> orderFilter10() {
        return registration(new OrderFilter("10"), 10, "/*");
    }

    @Bean
    FilterRegistrationBean<OrderFilter> orderFilter20() {
        return registration(new OrderFilter("20"), 20, "/*");
    }

    @Bean
    FilterRegistrationBean<SetHeaderFilter> scopedFilter() {
        return registration(new SetHeaderFilter("X-Scoped"), 5, "/filtered/*");
    }

    @Bean
    FilterRegistrationBean<SetHeaderFilter> exactFilter() {
        return registration(new SetHeaderFilter("X-Exact"), 5, "/api/greeting");
    }

    @Bean
    FilterRegistrationBean<ForbiddenFilter> forbiddenFilter() {
        return registration(new ForbiddenFilter(), 5, "/secure/*");
    }

    // Throwing filter declared BEFORE its error handler so the "upstream catches downstream" test
    // depends on @Order (HIGHEST_PRECEDENCE sorts first), not on declaration order.
    @Bean
    FilterRegistrationBean<ThrowingFilter> throwingFilter() {
        return registration(new ThrowingFilter(), 100, "/boom/*");
    }

    @Bean
    FilterRegistrationBean<ErrorHandlingFilter> errorHandlingFilter() {
        return registration(new ErrorHandlingFilter(), Ordered.HIGHEST_PRECEDENCE, "/boom/*");
    }

    private static <T extends Filter> FilterRegistrationBean<T> registration(T filter, int order, String urlPattern) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(urlPattern);
        registration.setOrder(order);
        return registration;
    }
}
