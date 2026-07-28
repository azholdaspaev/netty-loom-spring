package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration of the session cookie (issue #13).
 *
 * <p>Everything is held in one attribute map keyed by the canonical cookie-attribute names, exactly as
 * {@code jakarta.servlet.http.Cookie} itself does since Servlet 6.0 ({@code setPath} is
 * {@code putAttribute("Path", ...)}). That keeps the typed accessors and {@code getAttribute} from
 * drifting apart, and lets the emit path hand the whole map to a {@code Cookie} in one call.
 */
class NettySessionCookieConfigTest {

    private NettySessionCookieConfig config;

    @BeforeEach
    void setUp() {
        config = new NettySessionCookieConfig();
    }

    // --- Defaults ---

    @Test
    void nameDefaultsToJsessionid() {
        assertEquals("JSESSIONID", config.getName());
    }

    @Test
    void httpOnlyDefaultsToTrue() {
        assertTrue(config.isHttpOnly(),
            "The session cookie is HttpOnly unless configured otherwise, matching Tomcat's default");
    }

    @Test
    void secureDefaultsToFalse() {
        assertFalse(config.isSecure());
    }

    @Test
    void maxAgeDefaultsToMinusOne() {
        assertEquals(-1, config.getMaxAge(),
            "-1 marks a browser-session cookie, which NettyHttpServletResponse emits without Max-Age");
    }

    @Test
    void pathAndDomainDefaultToNull() {
        assertNull(config.getPath(), "An unset path is resolved per-request from the context path");
        assertNull(config.getDomain());
    }

    // --- The typed accessors and the attribute map are one store ---

    @Test
    void typedSettersAreVisibleThroughGetAttribute() {
        config.setPath("/app");
        config.setDomain("example.test");
        config.setMaxAge(60);

        assertEquals("/app", config.getAttribute("Path"));
        assertEquals("example.test", config.getAttribute("Domain"));
        assertEquals("60", config.getAttribute("Max-Age"));
    }

    @Test
    void flagsAreStoredAsPresenceWithAnEmptyValue() {
        // The encoding jakarta.servlet.http.Cookie uses. Storing the text "false" instead would read
        // back as *set* to anything following that model -- which is what the emit path does.
        config.setHttpOnly(false);
        config.setSecure(true);

        assertNull(config.getAttribute("HttpOnly"), "an unset flag is absent, not the string \"false\"");
        assertEquals("", config.getAttribute("Secure"));
        assertFalse(config.isHttpOnly());
        assertTrue(config.isSecure());
    }

    @Test
    void setAttributeIsVisibleThroughTheTypedGetters() {
        config.setAttribute("Path", "/app");
        config.setAttribute("Max-Age", "60");
        config.setAttribute("Secure", "");

        assertEquals("/app", config.getPath());
        assertEquals(60, config.getMaxAge());
        assertTrue(config.isSecure());
    }

    @Test
    void arbitraryAttributesRoundTrip() {
        config.setAttribute("SameSite", "Lax");
        config.setAttribute("Partitioned", "");

        assertEquals("Lax", config.getAttribute("SameSite"));
        assertEquals("", config.getAttribute("Partitioned"));
        assertTrue(config.getAttributes().containsKey("SameSite"));
        assertTrue(config.getAttributes().containsKey("Partitioned"));
    }

    @Test
    void settingAnAttributeToNullRemovesIt() {
        config.setAttribute("SameSite", "Lax");

        config.setAttribute("SameSite", null);

        assertNull(config.getAttribute("SameSite"));
    }

    @Test
    void getAttributesIsAnUnmodifiableSnapshot() {
        config.setAttribute("SameSite", "Lax");

        assertThrows(UnsupportedOperationException.class, () -> config.getAttributes().put("Secure", "true"));
    }

    @Test
    void attributeNamesAreMatchedCaseInsensitively() {
        config.setAttribute("path", "/app");
        config.setAttribute("samesite", "Lax");

        assertEquals("/app", config.getPath(),
            "Cookie attribute names are case-insensitive, so 'path' must reach the typed getter");
        assertEquals("Lax", config.getAttribute("SameSite"),
            "folding must apply to every attribute, not only the ones with a typed accessor");
    }

    // --- Removed-in-practice accessors ---

    @Test
    void commentAccessorsAreUnsupported() {
        // Cookie comments were dropped by RFC 6265; Netty's encoder has no Comment field either, so
        // silently accepting one would advertise support that cannot be honoured.
        assertNull(config.getComment());
        assertThrows(UnsupportedOperationException.class, () -> config.setComment("anything"));
    }
}
