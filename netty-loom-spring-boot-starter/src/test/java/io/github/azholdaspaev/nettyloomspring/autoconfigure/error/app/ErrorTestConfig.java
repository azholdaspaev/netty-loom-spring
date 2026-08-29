package io.github.azholdaspaev.nettyloomspring.autoconfigure.error.app;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.error.app.ErrorTestFixtures.TraceFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.error.ErrorPage;
import org.springframework.boot.web.error.ErrorPageRegistrar;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.EnumSet;

@Configuration
public class ErrorTestConfig {

    @Bean
    ErrorPageRegistrar gonePageRegistrar() {
        return registry -> registry.addErrorPages(new ErrorPage(HttpStatus.GONE, "/gone-page"));
    }

    @Bean
    FilterRegistrationBean<TraceFilter> requestOnlyFilter() {
        var registration = new FilterRegistrationBean<>(new TraceFilter("X-Request-Filter"));
        registration.addUrlPatterns("/error");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }

    @Bean
    FilterRegistrationBean<TraceFilter> errorOnlyFilter() {
        var registration = new FilterRegistrationBean<>(new TraceFilter("X-Error-Filter"));
        registration.addUrlPatterns("/error");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.ERROR));
        return registration;
    }

    @Bean
    UserDetailsService errorTestUsers() {
        return new InMemoryUserDetailsManager(
            User.withUsername("user").password("{noop}pw").roles("USER").build());
    }

    @Bean
    SecurityFilterChain errorTestSecurity(HttpSecurity http) {
        return http
            .authorizeHttpRequests(requests -> requests
                .requestMatchers("/error", "/gone-page").permitAll()
                .requestMatchers("/secured/denied").denyAll()
                .requestMatchers("/secured/**").authenticated()
                .anyRequest().permitAll())
            .httpBasic(Customizer.withDefaults())
            .build();
    }
}
