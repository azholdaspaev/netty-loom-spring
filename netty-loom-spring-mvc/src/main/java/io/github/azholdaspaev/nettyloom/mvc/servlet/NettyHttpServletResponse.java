package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.github.azholdaspaev.nettyloom.core.http.DefaultNettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.http.NettyHttpResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NettyHttpServletResponse implements HttpServletResponse {

    private int status = 200;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private String contentType;
    private String characterEncoding = "UTF-8";
    private final NettyServletOutputStream outputStream = new NettyServletOutputStream();
    private PrintWriter writer;

    @Override
    public void setStatus(int sc) {
        this.status = sc;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void setHeader(String name, String value) {
        List<String> values = new ArrayList<>();
        values.add(value);
        headers.put(name, values);
    }

    @Override
    public void addHeader(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(name);
        return values != null && !values.isEmpty() ? values.getFirst() : null;
    }

    @Override
    public Collection<String> getHeaders(String name) {
        List<String> values = headers.get(name);
        return values != null ? values : List.of();
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headers.keySet();
    }

    @Override
    public boolean containsHeader(String name) {
        return headers.containsKey(name);
    }

    @Override
    public void setContentType(String type) {
        this.contentType = type;
        setHeader("Content-Type", type);
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public NettyServletOutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() {
        if (writer == null) {
            writer = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
        }
        return writer;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        this.characterEncoding = charset;
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public void flushBuffer() {
        if (writer != null) {
            writer.flush();
        }
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    public NettyHttpResponse asNettyHttpResponse() {
        if (writer != null) {
            writer.flush();
        }
        return DefaultNettyHttpResponse.builder()
                .statusCode(status)
                .headers(headers)
                .body(outputStream.toByteArray())
                .build();
    }

    // --- Defaults ---

    @Override
    public void addCookie(Cookie cookie) {}

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    @Override
    public void sendError(int sc, String msg) {
        this.status = sc;
    }

    @Override
    public void sendError(int sc) {
        this.status = sc;
    }

    @Override
    public void sendRedirect(String location) {
        this.status = 302;
        setHeader("Location", location);
    }

    @Override
    public void setDateHeader(String name, long date) {
        setHeader(name, String.valueOf(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, String.valueOf(date));
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, String.valueOf(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        addHeader(name, String.valueOf(value));
    }

    @Override
    public void setContentLength(int len) {
        setHeader("Content-Length", String.valueOf(len));
    }

    @Override
    public void setContentLengthLong(long len) {
        setHeader("Content-Length", String.valueOf(len));
    }

    @Override
    public void setBufferSize(int size) {}

    @Override
    public int getBufferSize() {
        return 0;
    }

    @Override
    public void resetBuffer() {}

    @Override
    public void reset() {
        this.status = 200;
        this.headers.clear();
    }

    @Override
    public void setLocale(Locale loc) {}

    @Override
    public Locale getLocale() {
        return Locale.getDefault();
    }
}
