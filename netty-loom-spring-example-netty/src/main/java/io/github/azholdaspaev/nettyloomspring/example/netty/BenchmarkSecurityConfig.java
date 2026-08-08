package io.github.azholdaspaev.nettyloomspring.example.netty;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Identical to the Tomcat example's config so the benchmark targets serve the same work.
 * {@code securityMatcher} keeps {@code /ping} and {@code /work} outside the chain so the
 * {@code /work} vs {@code /work-secured} delta isolates what the chain costs — a measurement
 * device, not a configuration to copy. The no-op {@code PasswordEncoder} keeps login a string
 * compare: leaving the bean out is not equivalent, because {@code DelegatingPasswordEncoder}
 * reports {@code {noop}bench} as out of date and {@code InMemoryUserDetailsManager} re-encodes
 * the user with bcrypt on the first successful login, putting one key derivation per virtual
 * user into the measurement (issue #111).
 */
@Configuration
class BenchmarkSecurityConfig {

    @SuppressWarnings("deprecation") // the deprecation is the point: never do this outside a benchmark
    @Bean
    PasswordEncoder benchmarkPasswordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }

    @Bean
    SecurityFilterChain benchmarkSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/work-secured", "/login", "/logout")
            .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
            .formLogin(Customizer.withDefaults())
            .build();
    }
}
