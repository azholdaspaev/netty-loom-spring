package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyCookieSameSiteResolver;
import jakarta.servlet.http.Cookie;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;

import java.util.List;

/**
 * The application's {@link CookieSameSiteSupplier} beans, folded into the single policy the servlet
 * bridge reads. Named after Boot's own {@code SuppliedSameSiteCookieProcessor}, whose rules it
 * mirrors so that the same beans behave the same way here as on Tomcat.
 *
 * <p>The first supplier with an opinion wins, and {@link SameSite#OMITTED} is an opinion — "emit no
 * SameSite" — that ends the search rather than deferring to the next supplier.
 */
class SuppliedCookieSameSiteResolver implements NettyCookieSameSiteResolver {

    private final List<? extends CookieSameSiteSupplier> suppliers;

    SuppliedCookieSameSiteResolver(List<? extends CookieSameSiteSupplier> suppliers) {
        // Copied: ServletWebServerSettings.getCookieSameSiteSuppliers() hands back its live field.
        this.suppliers = List.copyOf(suppliers);
    }

    @Override
    public String resolve(Cookie cookie) {
        for (CookieSameSiteSupplier supplier : suppliers) {
            SameSite sameSite = supplier.getSameSite(cookie);
            if (sameSite != null) {
                return sameSite.attributeValue();
            }
        }
        return null;
    }
}
