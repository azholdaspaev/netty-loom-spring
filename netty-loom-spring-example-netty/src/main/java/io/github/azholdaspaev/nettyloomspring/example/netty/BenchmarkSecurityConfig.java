package io.github.azholdaspaev.nettyloomspring.example.netty;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Identical to the Tomcat example's config so the three benchmark targets serve the same work.
 *
 * <p>Scoped deliberately: {@code securityMatcher} keeps {@code /ping} and {@code /work} outside the
 * chain, so scenarios 1 and 2 stay comparable to the pre-Security snapshot and the
 * {@code /work} vs {@code /work-secured} delta isolates what the chain costs. A real application
 * would secure {@code anyRequest()} instead — this narrow matcher is a measurement device, not a
 * configuration to copy.
 *
 * <p>Two choices are load-bearing for the benchmark, not oversights:
 * <ul>
 *   <li>A no-op {@code PasswordEncoder}, so login is a string compare. Leaving the bean out is not
 *       equivalent: Boot then stores {@code {noop}bench}, but {@code DelegatingPasswordEncoder}
 *       reports every {@code {noop}} credential as out of date, so the first successful login has
 *       {@code InMemoryUserDetailsManager} re-encode the user with bcrypt and every login after it
 *       pays a full key derivation — one hash per virtual user, which makes the k6 ramp rather than
 *       the server the bottleneck being measured (issue #111).</li>
 *   <li>No {@code HttpSessionEventPublisher} (and so no {@code maximumSessions}), because the Netty
 *       bridge does not yet support {@code ServletContext.addListener} and would fail startup —
 *       and because both targets must run the same configuration for the comparison to mean
 *       anything.</li>
 * </ul>
 *
 * <p>CSRF stays enabled: the scenario measures the full chain, and the k6 script pays the one-off
 * cost of scraping the token during login rather than disabling the filter.
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
