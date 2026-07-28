package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import jakarta.servlet.SessionCookieConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Configuration of the {@code JSESSIONID} cookie (issue #13).
 *
 * <p>Every attribute lives in one case-insensitive map keyed by its cookie-attribute name, with the
 * typed accessors as views over it. That is exactly how {@code jakarta.servlet.http.Cookie} has
 * modelled itself since Servlet 6.0 -- {@code setPath} is {@code putAttribute("Path", ...)} over a
 * {@code TreeMap(String.CASE_INSENSITIVE_ORDER)}, and a boolean flag is <em>presence with an empty
 * value</em> rather than the text {@code "true"}. Matching that representation exactly is what lets the
 * emit path hand the whole map straight to a {@code Cookie} with nothing to fix up afterwards.
 */
public class NettySessionCookieConfig implements SessionCookieConfig {

    static final String DEFAULT_NAME = "JSESSIONID";

    /** -1 marks a browser-session cookie: {@code addCookie} then omits {@code Max-Age} entirely. */
    private static final int SESSION_COOKIE_MAX_AGE = -1;

    /** How {@code Cookie} encodes a set flag; absent means unset. */
    private static final String FLAG_SET = "";

    // Case-insensitive because cookie attribute names are, and Boot and Jakarta callers are not
    // consistent about which casing they use. Taking it from the map rather than folding a list of
    // known names means every attribute folds, not just the ones we happened to enumerate.
    private final ConcurrentMap<String, String> attributes =
        new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    private volatile String name = DEFAULT_NAME;

    public NettySessionCookieConfig() {
        // HttpOnly by default, matching Tomcat: the session id is never legitimately read by scripts.
        setHttpOnly(true);
    }

    @Override
    public void setName(String name) {
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
        throw new UnsupportedOperationException(
            "Cookie comments were removed by RFC 6265 and have no equivalent in Netty's cookie encoder");
    }

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
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public String getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Map<String, String> getAttributes() {
        return Map.copyOf(attributes);
    }

    private void setFlag(String name, boolean set) {
        setAttribute(name, set ? FLAG_SET : null);
    }
}
