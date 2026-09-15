package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration of the session cookie (issue #13).
 */
class NettySessionCookieConfigTest {

    private NettySessionCookieConfig config;

    @BeforeEach
    void setUp() {
        config = new NettySessionCookieConfig();
    }

    // --- Defaults ---

    @Test
    void shouldDefaultNameToJsessionid() {
        assertEquals(NettySessionCookieConfig.DEFAULT_NAME, config.getName());
    }

    @Test
    void shouldDefaultHttpOnlyToTrue() {
        assertTrue(config.isHttpOnly(),
            "The session cookie is HttpOnly unless configured otherwise, matching Tomcat's default");
    }

    @Test
    void shouldDefaultSecureToFalse() {
        assertFalse(config.isSecure());
    }

    @Test
    void shouldDefaultMaxAgeToMinusOne() {
        assertEquals(-1, config.getMaxAge(),
            "-1 marks a browser-session cookie, which NettyHttpServletResponse emits without Max-Age");
    }

    @Test
    void shouldDefaultPathAndDomainToNull() {
        assertNull(config.getPath(), "An unset path is resolved per-request from the context path");
        assertNull(config.getDomain());
    }

    // --- The typed accessors and the attribute map are one store ---

    @Test
    void shouldExposeTypedSettersThroughGetAttribute() {
        config.setPath("/app");
        config.setDomain("example.test");
        config.setMaxAge(60);

        assertEquals("/app", config.getAttribute("Path"));
        assertEquals("example.test", config.getAttribute("Domain"));
        assertEquals("60", config.getAttribute("Max-Age"));
    }

    @Test
    void shouldStoreFlagsAsPresenceWithEmptyValue() {
        config.setHttpOnly(false);
        config.setSecure(true);

        assertNull(config.getAttribute("HttpOnly"), "an unset flag is absent, not the string \"false\"");
        assertEquals("", config.getAttribute("Secure"));
        assertFalse(config.isHttpOnly());
        assertTrue(config.isSecure());
    }

    @Test
    void shouldExposeSetAttributeThroughTypedGetters() {
        config.setAttribute("Path", "/app");
        config.setAttribute("Max-Age", "60");
        config.setAttribute("Secure", "");

        assertEquals("/app", config.getPath());
        assertEquals(60, config.getMaxAge());
        assertTrue(config.isSecure());
    }

    @Test
    void shouldRoundTripArbitraryAttributes() {
        config.setAttribute("SameSite", "Lax");
        config.setAttribute("Partitioned", "");

        assertEquals("Lax", config.getAttribute("SameSite"));
        assertEquals("", config.getAttribute("Partitioned"));
        assertTrue(config.getAttributes().containsKey("SameSite"));
        assertTrue(config.getAttributes().containsKey("Partitioned"));
    }

    @Test
    void shouldRemoveCookieAttributeWhenSetToNull() {
        config.setAttribute("SameSite", "Lax");

        config.setAttribute("SameSite", null);

        assertNull(config.getAttribute("SameSite"));
    }

    @Test
    void shouldMakeGetAttributesCaseInsensitiveLikeBackingMap() {
        config.setPath("/app");

        assertEquals("/app", config.getAttributes().get("path"));
        assertEquals("/app", config.getAttributes().get("PATH"));
    }

    @Test
    void shouldRejectAttributeNameWithReservedCharacters() {
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute("Max Age", "600"));
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute("a;b", "c"));
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute("a=b", "c"));
    }

    @Test
    void shouldReturnUnmodifiableSnapshotFromGetAttributes() {
        config.setAttribute("SameSite", "Lax");

        assertThrows(UnsupportedOperationException.class, () -> config.getAttributes().put("Secure", "true"));
    }

    @Test
    void shouldMatchAttributeNamesCaseInsensitively() {
        config.setAttribute("path", "/app");
        config.setAttribute("samesite", "Lax");

        assertEquals("/app", config.getPath(),
            "Cookie attribute names are case-insensitive, so 'path' must reach the typed getter");
        assertEquals("Lax", config.getAttribute("SameSite"),
            "folding must apply to every attribute, not only the ones with a typed accessor");
    }

    // --- Removed-in-practice accessors ---

    @Test
    void shouldIgnoreSetCommentRatherThanReject() {
        assertDoesNotThrow(() -> config.setComment("anything"));
        assertNull(config.getComment());
    }

    // --- Validation and the post-initialization freeze ---

    @Test
    void shouldNormaliseBooleanAttributeGivenAsTextToFlagEncoding() {
        config.setAttribute("Partitioned", "false");
        assertNull(config.getAttribute("Partitioned"), "a false flag must be absent, not the text \"false\"");

        config.setAttribute("Partitioned", "true");
        assertEquals("", config.getAttribute("Partitioned"));

        config.setAttribute("HttpOnly", "false");
        assertFalse(config.isHttpOnly());
    }

    @Test
    void shouldRejectUnparseableMaxAgeWhereItIsConfigured() {
        assertThrows(NumberFormatException.class, () -> config.setAttribute("Max-Age", "forever"));
    }

    @Test
    void shouldRejectNullOrEmptyAttributeName() {
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute("", "x"));
    }

    @Test
    void shouldFreezeConfigurationOnceContextIsInitialized() {
        config.markInitialized();

        assertThrows(IllegalStateException.class, () -> config.setName("SID"));
        assertThrows(IllegalStateException.class, () -> config.setPath("/other"));
        assertThrows(IllegalStateException.class, () -> config.setHttpOnly(false));
        assertThrows(IllegalStateException.class, () -> config.setAttribute("SameSite", "Strict"));
    }

    @Test
    void shouldKeepReadingAvailableAfterFreeze() {
        config.setName("SID");
        config.markInitialized();

        assertEquals("SID", config.getName());
        assertTrue(config.isHttpOnly());
    }
}
