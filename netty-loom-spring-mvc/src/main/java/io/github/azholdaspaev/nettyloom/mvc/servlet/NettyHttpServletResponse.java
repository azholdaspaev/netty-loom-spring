package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Supplier;

/**
 * HttpServletResponse implementation that builds a Netty FullHttpResponse.
 * Buffers response data and converts to Netty format when requested.
 */
public class NettyHttpServletResponse implements HttpServletResponse {

    private static final String DEFAULT_CHARSET = "UTF-8";
    private static final String DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";

    private int status = SC_OK;
    private String statusMessage;
    private final HttpHeaders headers = new DefaultHttpHeaders();
    private final List<Cookie> cookies = new ArrayList<>();

    private String contentType;
    private String characterEncoding = DEFAULT_CHARSET;
    private long contentLength = -1;
    private Locale locale = Locale.getDefault();

    private NettyServletOutputStream outputStream;
    private PrintWriter writer;
    private boolean outputStreamUsed;
    private boolean writerUsed;
    private boolean committed;

    private final Supplier<String> serverHeader;

    /**
     * Creates a new response with no Server header.
     */
    public NettyHttpServletResponse() {
        this(() -> null);
    }

    /**
     * Creates a new response with a custom Server header supplier.
     *
     * @param serverHeader supplier for the Server header value
     */
    public NettyHttpServletResponse(Supplier<String> serverHeader) {
        this.serverHeader = serverHeader;
    }

    // ===== HttpServletResponse methods =====

    @Override
    public void addCookie(Cookie cookie) {
        cookies.add(cookie);
    }

    @Override
    public boolean containsHeader(String name) {
        return headers.contains(name);
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
        checkCommitted();
        resetBuffer();
        setStatus(sc);
        this.statusMessage = msg;
        committed = true;
    }

    @Override
    public void sendError(int sc) throws IOException {
        sendError(sc, null);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        checkCommitted();
        resetBuffer();
        setStatus(SC_FOUND);
        setHeader(HttpHeaderNames.LOCATION.toString(), location);
        committed = true;
    }

    @Override
    public void setDateHeader(String name, long date) {
        setHeader(name, formatDate(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, formatDate(date));
    }

    @Override
    public void setHeader(String name, String value) {
        if (!isCommitted()) {
            headers.set(name, value);
        }
    }

    @Override
    public void addHeader(String name, String value) {
        if (!isCommitted()) {
            headers.add(name, value);
        }
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
    public void setStatus(int sc) {
        this.status = sc;
        this.statusMessage = null;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return headers.getAll(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headers.names();
    }

    // ===== ServletResponse methods =====

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writerUsed) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        outputStreamUsed = true;
        if (outputStream == null) {
            outputStream = new NettyServletOutputStream();
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (outputStreamUsed) {
            throw new IllegalStateException("getOutputStream() has already been called");
        }
        writerUsed = true;
        if (writer == null) {
            Charset charset = Charset.forName(characterEncoding);
            outputStream = new NettyServletOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(outputStream, charset), false);
        }
        return writer;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        if (!isCommitted() && !writerUsed) {
            this.characterEncoding = charset != null ? charset : DEFAULT_CHARSET;
            updateContentTypeHeader();
        }
    }

    @Override
    public void setContentLength(int len) {
        setContentLengthLong(len);
    }

    @Override
    public void setContentLengthLong(long len) {
        if (!isCommitted()) {
            this.contentLength = len;
        }
    }

    @Override
    public void setContentType(String type) {
        if (isCommitted()) {
            return;
        }

        if (type == null) {
            this.contentType = null;
            headers.remove(HttpHeaderNames.CONTENT_TYPE);
            return;
        }

        // Parse charset from content type if present
        String lowerType = type.toLowerCase();
        int charsetIdx = lowerType.indexOf("charset=");
        if (charsetIdx >= 0 && !writerUsed) {
            int start = charsetIdx + 8;
            int end = type.indexOf(';', start);
            if (end < 0) end = type.length();
            String charset = type.substring(start, end).trim();
            if (!charset.isEmpty()) {
                this.characterEncoding = charset;
            }
        }

        this.contentType = type;
        updateContentTypeHeader();
    }

    @Override
    public void setBufferSize(int size) {
        checkCommitted();
        // Buffer size is managed by underlying stream
    }

    @Override
    public int getBufferSize() {
        return 8192; // Default buffer size
    }

    @Override
    public void flushBuffer() throws IOException {
        committed = true;
        if (writer != null) {
            writer.flush();
        }
        if (outputStream != null) {
            outputStream.flush();
        }
    }

    @Override
    public void resetBuffer() {
        checkCommitted();
        if (outputStream != null) {
            outputStream.reset();
        }
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void reset() {
        checkCommitted();
        status = SC_OK;
        statusMessage = null;
        headers.clear();
        cookies.clear();
        contentType = null;
        characterEncoding = DEFAULT_CHARSET;
        contentLength = -1;
        resetBuffer();
    }

    @Override
    public void setLocale(Locale loc) {
        if (loc != null && !isCommitted()) {
            this.locale = loc;
            // Set Content-Language header
            String language = loc.getLanguage();
            if (!language.isEmpty()) {
                String country = loc.getCountry();
                if (!country.isEmpty()) {
                    language = language + "-" + country;
                }
                setHeader(HttpHeaderNames.CONTENT_LANGUAGE.toString(), language);
            }
        }
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    // ===== Netty conversion =====

    /**
     * Converts this response to a Netty FullHttpResponse.
     *
     * @return the Netty response
     */
    public FullHttpResponse toNettyResponse() {
        // Flush writer if used
        if (writer != null) {
            writer.flush();
        }

        // Get response body
        byte[] body = outputStream != null ? outputStream.toByteArray() : new byte[0];
        ByteBuf content = Unpooled.wrappedBuffer(body);

        // Determine status
        HttpResponseStatus responseStatus;
        if (statusMessage != null) {
            responseStatus = new HttpResponseStatus(status, statusMessage);
        } else {
            responseStatus = HttpResponseStatus.valueOf(status);
        }

        // Create response
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                responseStatus,
                content
        );

        // Copy headers
        response.headers().add(headers);

        // Set Content-Length if not already set
        if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        }

        // Set Content-Type if not already set
        if (contentType != null && !response.headers().contains(HttpHeaderNames.CONTENT_TYPE)) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, buildContentTypeHeader());
        }

        // Add cookies
        for (Cookie cookie : cookies) {
            String encodedCookie = encodeCookie(cookie);
            response.headers().add(HttpHeaderNames.SET_COOKIE, encodedCookie);
        }

        // Add Server header if configured
        String server = serverHeader.get();
        if (server != null && !response.headers().contains(HttpHeaderNames.SERVER)) {
            response.headers().set(HttpHeaderNames.SERVER, server);
        }

        return response;
    }

    // ===== Helper methods =====

    private void checkCommitted() {
        if (committed) {
            throw new IllegalStateException("Response already committed");
        }
    }

    private void updateContentTypeHeader() {
        if (contentType != null) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, buildContentTypeHeader());
        }
    }

    private String buildContentTypeHeader() {
        if (contentType == null) {
            return null;
        }
        // If content type already includes charset, use as-is
        if (contentType.toLowerCase().contains("charset=")) {
            return contentType;
        }
        // Add charset for text types
        if (contentType.startsWith("text/") ||
            contentType.contains("json") ||
            contentType.contains("xml")) {
            return contentType + "; charset=" + characterEncoding;
        }
        return contentType;
    }

    private String formatDate(long date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT, Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return dateFormat.format(new Date(date));
    }

    private String encodeCookie(Cookie cookie) {
        DefaultCookie nettyCookie = new DefaultCookie(cookie.getName(), cookie.getValue());
        if (cookie.getDomain() != null) {
            nettyCookie.setDomain(cookie.getDomain());
        }
        if (cookie.getPath() != null) {
            nettyCookie.setPath(cookie.getPath());
        }
        nettyCookie.setMaxAge(cookie.getMaxAge());
        nettyCookie.setSecure(cookie.getSecure());
        nettyCookie.setHttpOnly(cookie.isHttpOnly());
        return ServerCookieEncoder.STRICT.encode(nettyCookie);
    }
}
