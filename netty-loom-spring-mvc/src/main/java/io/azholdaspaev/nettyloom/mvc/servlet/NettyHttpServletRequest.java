package io.azholdaspaev.nettyloom.mvc.servlet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AsciiString;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NettyHttpServletRequest implements HttpServletRequest {

    private final FullHttpRequest nettyRequest;

    private final Map<String, Object> attributes = new HashMap<>();
    private final String requestURI;
    private final String queryString;
    private final Map<String, String[]> parameterMap;
    private final Charset characterEncoding;
    private ServletInputStream inputStream;

    public NettyHttpServletRequest(FullHttpRequest nettyRequest) {
        this.nettyRequest = nettyRequest;

        QueryStringDecoder decoder = new QueryStringDecoder(nettyRequest.uri());
        this.requestURI = decoder.path();
        String rawQuery = decoder.rawQuery();
        this.queryString = rawQuery.isEmpty() ? null : rawQuery;
        this.characterEncoding = HttpUtil.getCharset(nettyRequest, null);

        Map<String, List<String>> merged = new LinkedHashMap<>(decoder.parameters());
        mergeFormBodyParameters(merged);
        this.parameterMap = toParameterMap(merged);
    }

    private void mergeFormBodyParameters(Map<String, List<String>> target) {
        CharSequence mimeType = HttpUtil.getMimeType(nettyRequest);
        if (mimeType == null
            || !AsciiString.contentEqualsIgnoreCase(mimeType, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED)) {
            return;
        }
        Charset charset = characterEncoding != null ? characterEncoding : StandardCharsets.UTF_8;
        String body = nettyRequest.content().toString(charset);
        if (body.isEmpty()) {
            return;
        }
        Map<String, List<String>> formParams =
            new QueryStringDecoder(body, charset, false).parameters();
        formParams.forEach((name, values) ->
            target.computeIfAbsent(name, k -> new ArrayList<>()).addAll(values));
    }

    private static Map<String, String[]> toParameterMap(Map<String, List<String>> parameters) {
        Map<String, String[]> map = new LinkedHashMap<>(parameters.size());
        parameters.forEach((name, values) -> map.put(name, values.toArray(new String[0])));
        return Collections.unmodifiableMap(map);
    }

    @Override
    public String getAuthType() {
        return "";
    }

    @Override
    public Cookie[] getCookies() {
        return new Cookie[0];
    }

    @Override
    public long getDateHeader(String name) {
        String value = nettyRequest.headers().get(name);
        if (value == null) {
            return -1L;
        }
        Date parsed = DateFormatter.parseHttpDate(value);
        if (parsed == null) {
            throw new IllegalArgumentException("Cannot parse date header: " + value);
        }
        return parsed.getTime();
    }

    @Override
    public String getHeader(String name) {
        return nettyRequest.headers().get(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(nettyRequest.headers().getAll(name));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(nettyRequest.headers().names());
    }

    @Override
    public int getIntHeader(String name) {
        String value = nettyRequest.headers().get(name);
        if (value == null) {
            return -1;
        }
        return Integer.parseInt(value);
    }

    @Override
    public String getMethod() {
        return nettyRequest.method().name();
    }

    @Override
    public String getPathInfo() {
        return "";
    }

    @Override
    public String getPathTranslated() {
        return "";
    }

    @Override
    public String getContextPath() {
        return "";
    }

    @Override
    public String getQueryString() {
        return queryString;
    }

    @Override
    public String getRemoteUser() {
        return "";
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public String getRequestedSessionId() {
        return "";
    }

    @Override
    public String getRequestURI() {
        return requestURI;
    }

    @Override
    public StringBuffer getRequestURL() {
        return null;
    }

    @Override
    public String getServletPath() {
        return "";
    }

    @Override
    public HttpSession getSession(boolean create) {
        return null;
    }

    @Override
    public HttpSession getSession() {
        return null;
    }

    @Override
    public String changeSessionId() {
        return "";
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
        return false;
    }

    @Override
    public void login(String username, String password) throws ServletException {

    }

    @Override
    public void logout() throws ServletException {

    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        return List.of();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        return null;
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException {
        return null;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding == null ? null : characterEncoding.name();
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {

    }

    @Override
    public int getContentLength() {
        long length = getContentLengthLong();
        return length > Integer.MAX_VALUE ? -1 : (int) length;
    }

    @Override
    public long getContentLengthLong() {
        return HttpUtil.getContentLength(nettyRequest, -1L);
    }

    @Override
    public String getContentType() {
        return nettyRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (inputStream == null) {
            ByteBuf buffer = nettyRequest.content().duplicate();
            ByteBufInputStream stream = new ByteBufInputStream(buffer);
            inputStream = new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.readableBytes() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Async read not supported");
                }

                @Override
                public int read() throws IOException {
                    return stream.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return stream.read(b, off, len);
                }
            };
        }
        return inputStream;
    }

    @Override
    public String getParameter(String name) {
        String[] values = parameterMap.get(name);
        return values == null ? null : values[0];
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameterMap.keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = parameterMap.get(name);
        return values == null ? null : values.clone();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return parameterMap;
    }

    @Override
    public String getProtocol() {
        return "";
    }

    @Override
    public String getScheme() {
        return "";
    }

    @Override
    public String getServerName() {
        return "";
    }

    @Override
    public int getServerPort() {
        return 0;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return null;
    }

    @Override
    public String getRemoteAddr() {
        return "";
    }

    @Override
    public String getRemoteHost() {
        return "";
    }

    @Override
    public void setAttribute(String name, Object o) {
        if (o == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, o);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public Locale getLocale() {
        return null;
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return null;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return null;
    }

    @Override
    public int getRemotePort() {
        return 0;
    }

    @Override
    public String getLocalName() {
        return "";
    }

    @Override
    public String getLocalAddr() {
        return "";
    }

    @Override
    public int getLocalPort() {
        return 0;
    }

    @Override
    public ServletContext getServletContext() {
        return null;
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        return null;
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException {
        return null;
    }

    @Override
    public boolean isAsyncStarted() {
        return false;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext getAsyncContext() {
        return null;
    }

    @Override
    public DispatcherType getDispatcherType() {
        return null;
    }

    @Override
    public String getRequestId() {
        return "";
    }

    @Override
    public String getProtocolRequestId() {
        return "";
    }

    @Override
    public ServletConnection getServletConnection() {
        return null;
    }
}
