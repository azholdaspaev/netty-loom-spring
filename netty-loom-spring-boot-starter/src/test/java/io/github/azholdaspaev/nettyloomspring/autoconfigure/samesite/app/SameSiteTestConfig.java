package io.github.azholdaspaev.nettyloomspring.autoconfigure.samesite.app;

import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettySessionCookieConfig;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application-supplied SameSite policy under test. The two matchers are deliberately disjoint, so
 * no assertion here depends on the order the beans are collected in -- that is
 * {@code SuppliedCookieSameSiteResolverTest}'s subject.
 */
@Configuration
public class SameSiteTestConfig {

    @Bean
    CookieSameSiteSupplier trackerSameSite() {
        return CookieSameSiteSupplier.ofStrict().whenHasName("tracker");
    }

    /**
     * Claims the session cookie too, so the test can show that
     * {@code server.servlet.session.cookie.same-site} still wins there.
     */
    @Bean
    CookieSameSiteSupplier sessionSameSite() {
        return CookieSameSiteSupplier.ofStrict().whenHasName(NettySessionCookieConfig.DEFAULT_NAME);
    }
}
