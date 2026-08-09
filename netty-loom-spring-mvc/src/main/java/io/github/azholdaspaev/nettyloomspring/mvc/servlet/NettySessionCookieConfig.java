package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import jakarta.servlet.SessionCookieConfig;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Configuration of the {@code JSESSIONID} cookie (issue #13). Every attribute lives in one
 * case-insensitive map keyed by its cookie-attribute name, with the typed accessors as views over it --
 * how {@code jakarta.servlet.http.Cookie} has modelled itself since Servlet 6.0, where {@code setPath} is
 * {@code putAttribute("Path", ...)} over a {@code TreeMap(String.CASE_INSENSITIVE_ORDER)} and a boolean
 * flag is presence with an empty value rather than the text {@code "true"}. Matching that exactly lets
 * the emit path hand the whole map straight to a {@code Cookie}.
 */
public class NettySessionCookieConfig implements SessionCookieConfig {

    /**
     * The servlet-conventional default; public so tests assert against it rather than the literal.
     */
    public static final String DEFAULT_NAME = "JSESSIONID";

    /**
     * -1 marks a browser-session cookie: {@code addCookie} then omits {@code Max-Age} entirely.
     */
    private static final int SESSION_COOKIE_MAX_AGE = -1;

    /**
     * RFC 6265 separators; {@code jakarta.servlet.http.Cookie} rejects the same set.
     */
    private static final String RESERVED_NAME_CHARACTERS = ",; \t()<>@:\"/[]?={}";

    /**
     * How {@code Cookie} encodes a set flag; absent means unset.
     */
    private static final String FLAG_SET = "";

    /**
     * The attributes whose presence, not value, carries the meaning.
     */
    private static final String[] FLAG_ATTRIBUTES = {
        CookieHeaderNames.SECURE, CookieHeaderNames.HTTPONLY, CookieHeaderNames.PARTITIONED};

    // Case-insensitive because cookie attribute names are, and Boot and Jakarta callers are not
    // consistent about which casing they use. Taking it from the map rather than folding a list of
    // known names means every attribute folds, not just the ones we happened to enumerate.
    private final ConcurrentMap<String, String> attributes =
        new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    private volatile String name = DEFAULT_NAME;
    private volatile boolean initialized;

    public NettySessionCookieConfig() {
        // HttpOnly by default, matching Tomcat: the session id is never legitimately read by scripts.
        setHttpOnly(true);
    }

    @Override
    public void setName(String name) {
        requireNotInitialized();
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setDomain(String domain) {
        setAttribute(CookieHeaderNames.DOMAIN, domain);
    }

    @Override
    public String getDomain() {
        return getAttribute(CookieHeaderNames.DOMAIN);
    }

    @Override
    public void setPath(String path) {
        setAttribute(CookieHeaderNames.PATH, path);
    }

    @Override
    public String getPath() {
        return getAttribute(CookieHeaderNames.PATH);
    }

    @Override
    public void setComment(String comment) {
        // Specified as "If called, this method has no effect" since Servlet 6.0, and deprecated for
        // removal. Throwing would abort context refresh for any legacy initializer that defensively
        // calls it, where Tomcat and Jetty start fine.
        requireNotInitialized();
    }

    /**
     * {@inheritDoc}
     * Always {@code null}: RFC 6265 dropped the attribute and Netty's encoder has no field for it.
     */
    @Override
    public String getComment() {
        return null;
    }

    @Override
    public void setHttpOnly(boolean httpOnly) {
        setFlag(CookieHeaderNames.HTTPONLY, httpOnly);
    }

    @Override
    public boolean isHttpOnly() {
        return getAttribute(CookieHeaderNames.HTTPONLY) != null;
    }

    @Override
    public void setSecure(boolean secure) {
        setFlag(CookieHeaderNames.SECURE, secure);
    }

    @Override
    public boolean isSecure() {
        return getAttribute(CookieHeaderNames.SECURE) != null;
    }

    @Override
    public void setMaxAge(int maxAge) {
        setAttribute(CookieHeaderNames.MAX_AGE, Integer.toString(maxAge));
    }

    @Override
    public int getMaxAge() {
        String maxAge = getAttribute(CookieHeaderNames.MAX_AGE);
        return maxAge == null ? SESSION_COOKIE_MAX_AGE : Integer.parseInt(maxAge);
    }

    @Override
    public void setAttribute(String name, String value) {
        requireNotInitialized();
        requireValidAttributeName(name);
        if (value == null) {
            attributes.remove(name);
            return;
        }
        if (isFlag(name)) {
            // Boolean attributes are presence-encoded, but callers hand them over as text: Boot maps
            // server.servlet.session.cookie.partitioned through Object::toString, so a configured `false`
            // arrives here as the string "false" and, stored verbatim, would emit the flag it was meant
            // to suppress.
            setFlag(name, !Boolean.toString(false).equalsIgnoreCase(value));
            return;
        }
        if (CookieHeaderNames.MAX_AGE.equalsIgnoreCase(name)) {
            // Parsed now rather than at the first session-creating request, where it would surface as a
            // 500 with a stack trace pointing nowhere near the misconfiguration.
            Integer.parseInt(value);
        }
        attributes.put(name, value);
    }

    @Override
    public String getAttribute(String name) {
        return attributes.get(name);
    }

    /**
     * {@inheritDoc}
     * Case-insensitive, as the contract requires. {@code Map.copyOf} would key the copy by plain
     * {@code equals} and silently drop the comparator, so {@code getAttribute("path")} and
     * {@code getAttributes().get("path")} would disagree.
     */
    @Override
    public Map<String, String> getAttributes() {
        TreeMap<String, String> snapshot = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        snapshot.putAll(attributes);
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Freezes the configuration, as every setter's {@code IllegalStateException} clause requires once
     * the owning {@code ServletContext} has been initialized. Without it a bean holding the context
     * could rename the cookie at runtime and orphan every logged-in user's session.
     */
    void markInitialized() {
        this.initialized = true;
    }

    private void requireNotInitialized() {
        if (initialized) {
            throw new IllegalStateException(
                "The session cookie cannot be reconfigured once the ServletContext has been initialized");
        }
    }

    /**
     * Rejects a name {@code jakarta.servlet.http.Cookie} would reject later anyway, but here where the
     * misconfiguration is rather than as a 500 on the first session-creating request.
     */
    private static void requireValidAttributeName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Cookie attribute name must not be null or empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // RFC 6265 token: no CTLs, no separators.
            if (c < 0x20 || c >= 0x7f || RESERVED_NAME_CHARACTERS.indexOf(c) >= 0) {
                throw new IllegalArgumentException(
                    "Cookie attribute name '" + name + "' contains a character not permitted in a cookie name");
            }
        }
    }

    private static boolean isFlag(String name) {
        for (String flag : FLAG_ATTRIBUTES) {
            if (flag.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void setFlag(String name, boolean set) {
        requireNotInitialized();
        if (set) {
            attributes.put(name, FLAG_SET);
        } else {
            attributes.remove(name);
        }
    }
}
