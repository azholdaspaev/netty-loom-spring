package io.github.azholdaspaev.nettyloomspring.integration.app;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class RestServiceSecurityConfig {

    public static final String USERNAME = "user";

    public static final String PASSWORD = "password";

    // Declared, not left to Boot: UserDetailsServiceAutoConfiguration backs off beside a resource server.
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(
            User.withUsername(USERNAME).password("{noop}" + PASSWORD).roles("USER").build());
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(requests -> requests
                .requestMatchers("/api/me").authenticated()
                .anyRequest().permitAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
            .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain formLoginSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(requests -> requests
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/form/**").authenticated()
                .anyRequest().permitAll())
            .formLogin(Customizer.withDefaults())
            .build();
    }
}
