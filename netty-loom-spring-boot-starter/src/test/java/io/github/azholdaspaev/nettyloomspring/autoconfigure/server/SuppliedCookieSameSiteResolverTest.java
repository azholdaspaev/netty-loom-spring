package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fold from Boot's supplier list onto the single policy the servlet bridge reads (issue #85).
 * The rules mirrored here are those of Boot's own {@code SuppliedSameSiteCookieProcessor}, which is
 * what a Tomcat deployment of the same beans would apply.
 */
class SuppliedCookieSameSiteResolverTest {

    @Test
    void theFirstSupplierWithAnOpinionWins() {
        var resolver = new SuppliedCookieSameSiteResolver(List.of(
            CookieSameSiteSupplier.ofNone().whenHasName("tracker"),
            CookieSameSiteSupplier.ofStrict()));

        assertEquals("None", resolver.resolve(new Cookie("tracker", "t")));
    }

    @Test
    void aSupplierWithNoOpinionIsSkipped() {
        var resolver = new SuppliedCookieSameSiteResolver(List.of(
            CookieSameSiteSupplier.ofNone().whenHasName("other"),
            CookieSameSiteSupplier.ofStrict()));

        assertEquals("Strict", resolver.resolve(new Cookie("tracker", "t")));
    }

    @Test
    void anOmittedSameSiteStopsAtThatSupplierAndSuppressesTheRest() {
        // SameSite.OMITTED is an opinion -- "emit nothing" -- not an abstention, so the search ends
        // there. Reading past it would let the next supplier's Strict appear where Tomcat emits no
        // SameSite at all.
        var resolver = new SuppliedCookieSameSiteResolver(List.of(
            CookieSameSiteSupplier.of(SameSite.OMITTED),
            CookieSameSiteSupplier.ofStrict()));

        assertNull(resolver.resolve(new Cookie("tracker", "t")));
    }

    @Test
    void noSupplierWithAnOpinionYieldsNoSameSite() {
        var resolver = new SuppliedCookieSameSiteResolver(List.of(
            CookieSameSiteSupplier.ofStrict().whenHasName("other")));

        assertNull(resolver.resolve(new Cookie("tracker", "t")));
    }

    @Test
    void theSupplierListIsCopiedOnConstruction() {
        // ServletWebServerSettings.getCookieSameSiteSuppliers() hands back its live field, not a copy.
        var suppliers = new ArrayList<CookieSameSiteSupplier>();
        suppliers.add(CookieSameSiteSupplier.ofLax());
        var resolver = new SuppliedCookieSameSiteResolver(suppliers);

        suppliers.clear();

        assertEquals("Lax", resolver.resolve(new Cookie("tracker", "t")));
    }
}
