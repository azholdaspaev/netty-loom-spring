package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.FastByteArrayOutputStream;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class NettyHttpServletResponse implements HttpServletResponse {

    // Hoisted so parseSameSite doesn't clone a fresh enum array on every addCookie (write path).
    private static final CookieHeaderNames.SameSite[] SAME_SITE_VALUES = CookieHeaderNames.SameSite.values();

    private final FastByteArrayOutputStream body = new FastByteArrayOutputStream(256);
    private final HttpHeaders headers = new HttpHeaders();
    private int status = HttpServletResponse.SC_OK;
    private Charset characterEncoding = StandardCharsets.ISO_8859_1;
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    @Override
    public void addCookie(Cookie cookie) {
        DefaultCookie nettyCookie = new DefaultCookie(cookie.getName(), Objects.requireNonNullElse(cookie.getValue(), ""));
        if (cookie.getPath() != null) {
            nettyCookie.setPath(cookie.getPath());
        }
        if (cookie.getDomain() != null) {
            nettyCookie.setDomain(cookie.getDomain());
        }
        // maxAge -1 marks a session cookie: leave Max-Age unset rather than emitting it.
        if (cookie.getMaxAge() >= 0) {
            nettyCookie.setMaxAge(cookie.getMaxAge());
        }
        nettyCookie.setSecure(cookie.getSecure());
        nettyCookie.setHttpOnly(cookie.isHttpOnly());
        CookieHeaderNames.SameSite sameSite = parseSameSite(cookie.getAttribute(CookieHeaderNames.SAMESITE));
        if (sameSite != null) {
            nettyCookie.setSameSite(sameSite);
        }
        // CHIPS: propagate Partitioned when the attribute is present (its value is conventionally empty).
        if (cookie.getAttribute(CookieHeaderNames.PARTITIONED) != null) {
            nettyCookie.setPartitioned(true);
        }
        // Only the attributes above are mapped; Netty's Cookie has no arbitrary-attribute setter, so any
        // other Servlet 6.0 attribute (e.g. Expires) is dropped. version is likewise NOT propagated: RFC
        // 6265 / Netty have no Version field.
        // STRICT validates RFC 6265 name/value octets and throws IllegalArgumentException for invalid
        // input; we let it propagate (fail-fast, matching Tomcat's Rfc6265CookieProcessor) rather than
        // silently dropping or mangling the cookie.
        headers.add(HttpHeaders.SET_COOKIE, ServerCookieEncoder.STRICT.encode(nettyCookie));
    }

    private static CookieHeaderNames.SameSite parseSameSite(String value) {
        if (value == null) {
            return null;
        }
        // Trim so a padded value (e.g. " Strict ") isn't silently dropped, weakening the policy.
        String trimmed = value.trim();
        for (CookieHeaderNames.SameSite candidate : SAME_SITE_VALUES) {
            if (candidate.name().equalsIgnoreCase(trimmed)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public boolean containsHeader(String name) {
        return headers.containsHeader(name);
    }

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        discardBody();
        this.status = sc;
    }

    @Override
    public void sendError(int sc) throws IOException {
        discardBody();
        this.status = sc;
    }

    private void discardBody() {
        // Per the Servlet spec, sendError clears the response buffer (but not other headers/status).
        resetBuffer();
        // The body is now empty, so a previously-set Content-Length would mis-frame the response
        // (client hangs waiting for bytes that never arrive); drop it and let toFullHttpResponse
        // recompute Content-Length: 0.
        headers.remove(HttpHeaders.CONTENT_LENGTH);
    }

    @Override
    public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        this.status = sc;
        setHeader(HttpHeaders.LOCATION, location);
    }

    @Override
    public void setDateHeader(String name, long date) {
        setHeader(name, DateFormatter.format(new Date(date)));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, DateFormatter.format(new Date(date)));
    }

    @Override
    public void setHeader(String name, String value) {
        if (value == null) {
            headers.remove(name);
            return;
        }
        headers.set(name, value);
        updateCharsetIfContentType(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if (value == null) {
            return;
        }
        headers.add(name, value);
        updateCharsetIfContentType(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        addHeader(name, Integer.toString(value));
    }

    @Override
    public void setStatus(int sc) {
        this.status = sc;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public String getHeader(String name) {
        return headers.getFirst(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        List<String> values = headers.get(name);
        return values == null ? List.of() : List.copyOf(values);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return List.copyOf(headers.headerNames());
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding.name();
    }

    @Override
    public String getContentType() {
        return headers.getFirst(HttpHeaders.CONTENT_TYPE);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (outputStream == null) {
            outputStream = new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                }

                @Override
                public void write(int b) throws IOException {
                    body.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    body.write(b, off, len);
                }
            };
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(body, characterEncoding), false);
        }
        return writer;
    }

    @Override
    public void setCharacterEncoding(String encoding) {
        this.characterEncoding = Charset.forName(encoding);
    }

    @Override
    public void setContentLength(int len) {
        headers.setContentLength(len);
    }

    @Override
    public void setContentLengthLong(long len) {
        headers.setContentLength(len);
    }

    @Override
    public void setContentType(String type) {
        setHeader(HttpHeaders.CONTENT_TYPE, type);
    }

    @Override
    public void setBufferSize(int size) {
    }

    @Override
    public int getBufferSize() {
        return 0;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
    }

    @Override
    public void resetBuffer() {
        body.reset();
        // Drop the cached writer/stream too: the writer (autoFlush=false) holds an encoder buffer
        // whose unflushed chars would otherwise be re-flushed into the body by toFullHttpResponse().
        writer = null;
        outputStream = null;
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    public void reset() {
        resetBuffer();
        headers.clear();
        status = HttpServletResponse.SC_OK;
        characterEncoding = StandardCharsets.ISO_8859_1;
    }

    @Override
    public void setLocale(Locale loc) {
    }

    @Override
    public Locale getLocale() {
        return Locale.getDefault();
    }

    private void updateCharsetIfContentType(String name, String value) {
        if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
            return;
        }
        try {
            Charset parsed = MediaType.parseMediaType(value).getCharset();
            if (parsed != null) {
                characterEncoding = parsed;
            }
        } catch (InvalidMediaTypeException ignored) {
        }
    }

    public FullHttpResponse toFullHttpResponse() throws IOException {
        flushBuffer();
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(status),
            Unpooled.wrappedBuffer(body.toByteArrayUnsafe(), 0, body.size())
        );
        headers.forEach((name, values) -> response.headers().add(name, values));
        if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.size());
        }
        return response;
    }
}
