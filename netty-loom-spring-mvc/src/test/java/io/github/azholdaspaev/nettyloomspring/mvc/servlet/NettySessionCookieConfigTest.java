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
    void nameDefaultsToJsessionid() {
        assertEquals(NettySessionCookieConfig.DEFAULT_NAME, config.getName());
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
    void getAttributesIsCaseInsensitiveLikeTheBackingMap() {
        config.setPath("/app");

        assertEquals("/app", config.getAttributes().get("path"));
        assertEquals("/app", config.getAttributes().get("PATH"));
    }

    @Test
    void anAttributeNameWithReservedCharactersIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute("Max Age", "600"));
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute("a;b", "c"));
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute("a=b", "c"));
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
    void setCommentIsIgnoredRatherThanRejected() {
        assertDoesNotThrow(() -> config.setComment("anything"));
        assertNull(config.getComment());
    }

    // --- Validation and the post-initialization freeze ---

    @Test
    void aBooleanAttributeGivenAsTextIsNormalisedToTheFlagEncoding() {
        config.setAttribute("Partitioned", "false");
        assertNull(config.getAttribute("Partitioned"), "a false flag must be absent, not the text \"false\"");

        config.setAttribute("Partitioned", "true");
        assertEquals("", config.getAttribute("Partitioned"));

        config.setAttribute("HttpOnly", "false");
        assertFalse(config.isHttpOnly());
    }

    @Test
    void anUnparseableMaxAgeIsRejectedWhereItIsConfigured() {
        assertThrows(NumberFormatException.class, () -> config.setAttribute("Max-Age", "forever"));
    }

    @Test
    void aNullOrEmptyAttributeNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> config.setAttribute("", "x"));
    }

    @Test
    void theConfigurationIsFrozenOnceTheContextIsInitialized() {
        config.markInitialized();

        assertThrows(IllegalStateException.class, () -> config.setName("SID"));
        assertThrows(IllegalStateException.class, () -> config.setPath("/other"));
        assertThrows(IllegalStateException.class, () -> config.setHttpOnly(false));
        assertThrows(IllegalStateException.class, () -> config.setAttribute("SameSite", "Strict"));
    }

    @Test
    void readingStaysAvailableAfterTheFreeze() {
        config.setName("SID");
        config.markInitialized();

        assertEquals("SID", config.getName());
        assertTrue(config.isHttpOnly());
    }
}
