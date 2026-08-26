package io.github.azholdaspaev.nettyloomspring.autoconfigure.forward.app;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.forward.app.ForwardTestFixtures.TraceFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;

@Configuration
public class ForwardTestConfig {

    @Bean
    FilterRegistrationBean<TraceFilter> requestOnlyFilter() {
        var registration = new FilterRegistrationBean<>(new TraceFilter("request"));
        registration.addUrlPatterns("/forward/*");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    @Bean
    FilterRegistrationBean<TraceFilter> forwardOnlyFilter() {
        var registration = new FilterRegistrationBean<>(new TraceFilter("forward"));
        registration.addUrlPatterns("/forward/target");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.FORWARD));
        return registration;
    }
}
