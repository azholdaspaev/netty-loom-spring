package io.github.azholdaspaev.nettyloom.mvc.servlet;

import io.github.azholdaspaev.nettyloom.mvc.request.ParameterParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HttpServletRequest implementation that adapts a Netty FullHttpRequest
 * for use with Spring MVC and the Servlet API.
 */
public class NettyHttpServletRequest implements HttpServletRequest {

    private final FullHttpRequest nettyRequest;
    private final ChannelHandlerContext ctx;
    private final ServletContext servletContext;
    private final String contextPath;

    private final Map<String, Object> attributes = new HashMap<>();
    private Map<String, String[]> parameterMap;
    private NettyServletInputStream inputStream;
    private BufferedReader reader;
    private String characterEncoding;
    private boolean inputStreamUsed;
    private boolean readerUsed;

    // Cached parsed values
    private String requestURI;
    private String queryString;
    private String servletPath;
    private String pathInfo;
    private List<jakarta.servlet.http.Cookie> cookies;

    /**
     * Creates a new request adapter.
     *
     * @param nettyRequest the Netty HTTP request
     * @param ctx the channel handler context
     * @param servletContext the servlet context
     * @param contextPath the context path (e.g., "/api")
     */
    public NettyHttpServletRequest(FullHttpRequest nettyRequest,
                                   ChannelHandlerContext ctx,
                                   ServletContext servletContext,
                                   String contextPath) {
        this.nettyRequest = nettyRequest;
        this.ctx = ctx;
        this.servletContext = servletContext;
        this.contextPath = contextPath != null ? contextPath : "";
    }

    /**
     * Creates a new request adapter with empty context path.
     *
     * @param nettyRequest the Netty HTTP request
     * @param ctx the channel handler context
     * @param servletContext the servlet context
     */
    public NettyHttpServletRequest(FullHttpRequest nettyRequest,
                                   ChannelHandlerContext ctx,
                                   ServletContext servletContext) {
        this(nettyRequest, ctx, servletContext, "");
    }

    // ===== HttpServletRequest methods =====

    @Override
    public String getAuthType() {
        return null;
    }

    @Override
    public jakarta.servlet.http.Cookie[] getCookies() {
        if (cookies == null) {
            cookies = parseCookies();
        }
        return cookies.isEmpty() ? null : cookies.toArray(new jakarta.servlet.http.Cookie[0]);
    }

    private List<jakarta.servlet.http.Cookie> parseCookies() {
        String cookieHeader = nettyRequest.headers().get(HttpHeaderNames.COOKIE);
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return Collections.emptyList();
        }

        List<jakarta.servlet.http.Cookie> result = new ArrayList<>();
        Set<Cookie> nettyCookies = ServerCookieDecoder.STRICT.decode(cookieHeader);
        for (Cookie nettyCookie : nettyCookies) {
            jakarta.servlet.http.Cookie servletCookie =
                    new jakarta.servlet.http.Cookie(nettyCookie.name(), nettyCookie.value());
            if (nettyCookie.domain() != null) {
                servletCookie.setDomain(nettyCookie.domain());
            }
            if (nettyCookie.path() != null) {
                servletCookie.setPath(nettyCookie.path());
            }
            servletCookie.setMaxAge((int) nettyCookie.maxAge());
            servletCookie.setSecure(nettyCookie.isSecure());
            servletCookie.setHttpOnly(nettyCookie.isHttpOnly());
            result.add(servletCookie);
        }
        return result;
    }

    @Override
    public long getDateHeader(String name) {
        String value = nettyRequest.headers().get(name);
        if (value == null) {
            return -1;
        }
        try {
            return io.netty.handler.codec.DateFormatter.parseHttpDate(value).getTime();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse date header: " + value);
        }
    }

    @Override
    public String getHeader(String name) {
        return nettyRequest.headers().get(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        List<String> values = nettyRequest.headers().getAll(name);
        return Collections.enumeration(values);
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
        parseRequestPath();
        return pathInfo;
    }

    @Override
    public String getPathTranslated() {
        String pi = getPathInfo();
        return pi == null ? null : servletContext.getRealPath(pi);
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public String getQueryString() {
        parseRequestPath();
        return queryString;
    }

    @Override
    public String getRemoteUser() {
        return null;
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
        return null;
    }

    @Override
    public String getRequestURI() {
        parseRequestPath();
        return requestURI;
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer url = new StringBuffer();
        String scheme = getScheme();
        int port = getServerPort();

        url.append(scheme).append("://").append(getServerName());

        if ((scheme.equals("http") && port != 80) ||
            (scheme.equals("https") && port != 443)) {
            url.append(':').append(port);
        }

        url.append(getRequestURI());
        return url;
    }

    @Override
    public String getServletPath() {
        parseRequestPath();
        return servletPath;
    }

    @Override
    public HttpSession getSession(boolean create) {
        // Session support not implemented in this adapter
        return null;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public String changeSessionId() {
        throw new UnsupportedOperationException("Session not supported");
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
        throw new UnsupportedOperationException("Authentication not supported");
    }

    @Override
    public void login(String username, String password) throws ServletException {
        throw new UnsupportedOperationException("Login not supported");
    }

    @Override
    public void logout() throws ServletException {
        throw new UnsupportedOperationException("Logout not supported");
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        throw new UnsupportedOperationException("Multipart not supported");
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        throw new UnsupportedOperationException("Multipart not supported");
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass)
            throws IOException, ServletException {
        throw new UnsupportedOperationException("HTTP upgrade not supported");
    }

    // ===== ServletRequest methods =====

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
        if (characterEncoding != null) {
            return characterEncoding;
        }
        return parseCharsetFromContentType();
    }

    @Override
    public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        // Validate encoding
        Charset.forName(env);
        this.characterEncoding = env;
    }

    @Override
    public int getContentLength() {
        return (int) getContentLengthLong();
    }

    @Override
    public long getContentLengthLong() {
        String contentLength = nettyRequest.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLength == null) {
            return -1;
        }
        try {
            return Long.parseLong(contentLength);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public String getContentType() {
        return nettyRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (readerUsed) {
            throw new IllegalStateException("getReader() has already been called");
        }
        inputStreamUsed = true;
        if (inputStream == null) {
            inputStream = new NettyServletInputStream(nettyRequest.content());
        }
        return inputStream;
    }

    @Override
    public String getParameter(String name) {
        String[] values = getParameterMap().get(name);
        return values != null && values.length > 0 ? values[0] : null;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(getParameterMap().keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        return getParameterMap().get(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (parameterMap == null) {
            parameterMap = parseParameters();
        }
        return parameterMap;
    }

    private Map<String, String[]> parseParameters() {
        parseRequestPath();
        Charset charset = getRequestCharset();

        // Parse query string parameters
        Map<String, List<String>> params = ParameterParser.parseQueryString(queryString, charset);

        // Parse form body if applicable
        String contentType = getContentType();
        if (ParameterParser.isFormUrlEncoded(contentType)) {
            ByteBuf content = nettyRequest.content();
            if (content.isReadable()) {
                byte[] bytes = new byte[content.readableBytes()];
                content.getBytes(content.readerIndex(), bytes);
                String body = new String(bytes, charset);
                Map<String, List<String>> bodyParams = ParameterParser.parseFormBody(body, charset);
                params = ParameterParser.merge(params, bodyParams);
            }
        }

        return ParameterParser.toArrayMap(params);
    }

    @Override
    public String getProtocol() {
        return nettyRequest.protocolVersion().text();
    }

    @Override
    public String getScheme() {
        // Could be enhanced to detect SSL from channel pipeline
        return "http";
    }

    @Override
    public String getServerName() {
        String host = nettyRequest.headers().get(HttpHeaderNames.HOST);
        if (host == null) {
            return "localhost";
        }
        int colonIdx = host.indexOf(':');
        return colonIdx > 0 ? host.substring(0, colonIdx) : host;
    }

    @Override
    public int getServerPort() {
        String host = nettyRequest.headers().get(HttpHeaderNames.HOST);
        if (host != null) {
            int colonIdx = host.indexOf(':');
            if (colonIdx > 0) {
                try {
                    return Integer.parseInt(host.substring(colonIdx + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return getScheme().equals("https") ? 443 : 80;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (inputStreamUsed) {
            throw new IllegalStateException("getInputStream() has already been called");
        }
        readerUsed = true;
        if (reader == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            // Create input stream directly to avoid mutual exclusion check
            if (inputStream == null) {
                inputStream = new NettyServletInputStream(nettyRequest.content());
            }
            reader = new BufferedReader(new InputStreamReader(inputStream, charset));
        }
        return reader;
    }

    @Override
    public String getRemoteAddr() {
        if (ctx != null && ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return "127.0.0.1";
    }

    @Override
    public String getRemoteHost() {
        if (ctx != null && ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getHostName();
        }
        return "localhost";
    }

    @Override
    public void setAttribute(String name, Object o) {
        if (o == null) {
            removeAttribute(name);
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
        Enumeration<Locale> locales = getLocales();
        return locales.hasMoreElements() ? locales.nextElement() : Locale.getDefault();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        String acceptLanguage = nettyRequest.headers().get(HttpHeaderNames.ACCEPT_LANGUAGE);
        if (acceptLanguage == null || acceptLanguage.isEmpty()) {
            return Collections.enumeration(Collections.singletonList(Locale.getDefault()));
        }

        List<Locale> locales = new ArrayList<>();
        String[] languages = acceptLanguage.split(",");
        for (String lang : languages) {
            String trimmed = lang.trim();
            int semicolonIdx = trimmed.indexOf(';');
            if (semicolonIdx > 0) {
                trimmed = trimmed.substring(0, semicolonIdx).trim();
            }
            locales.add(Locale.forLanguageTag(trimmed));
        }

        return Collections.enumeration(locales.isEmpty() ?
                Collections.singletonList(Locale.getDefault()) : locales);
    }

    @Override
    public boolean isSecure() {
        return "https".equals(getScheme());
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return servletContext != null ? servletContext.getRequestDispatcher(path) : null;
    }

    @Override
    public int getRemotePort() {
        if (ctx != null && ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getPort();
        }
        return 0;
    }

    @Override
    public String getLocalName() {
        if (ctx != null && ctx.channel().localAddress() instanceof InetSocketAddress addr) {
            return addr.getHostName();
        }
        return "localhost";
    }

    @Override
    public String getLocalAddr() {
        if (ctx != null && ctx.channel().localAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return "127.0.0.1";
    }

    @Override
    public int getLocalPort() {
        if (ctx != null && ctx.channel().localAddress() instanceof InetSocketAddress addr) {
            return addr.getPort();
        }
        return 80;
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        throw new UnsupportedOperationException("Async not supported");
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
            throws IllegalStateException {
        throw new UnsupportedOperationException("Async not supported");
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
        throw new IllegalStateException("Async not started");
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    @Override
    public String getRequestId() {
        return String.valueOf(System.identityHashCode(this));
    }

    @Override
    public String getProtocolRequestId() {
        return "";
    }

    @Override
    public ServletConnection getServletConnection() {
        return null;
    }

    // ===== Helper methods =====

    private void parseRequestPath() {
        if (requestURI != null) {
            return;
        }

        String uri = nettyRequest.uri();
        int queryIdx = uri.indexOf('?');

        if (queryIdx >= 0) {
            requestURI = uri.substring(0, queryIdx);
            queryString = uri.substring(queryIdx + 1);
        } else {
            requestURI = uri;
            queryString = null;
        }

        // Compute servlet path (URI minus context path)
        if (contextPath.isEmpty() || !requestURI.startsWith(contextPath)) {
            servletPath = requestURI;
            pathInfo = null;
        } else {
            servletPath = requestURI.substring(contextPath.length());
            if (servletPath.isEmpty()) {
                servletPath = "/";
            }
            pathInfo = null;
        }
    }

    private String parseCharsetFromContentType() {
        String contentType = getContentType();
        if (contentType == null) {
            return null;
        }

        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("charset=")) {
                return trimmed.substring(8).trim();
            }
        }
        return null;
    }

    private Charset getRequestCharset() {
        String encoding = getCharacterEncoding();
        if (encoding != null) {
            try {
                return Charset.forName(encoding);
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Returns the underlying Netty request.
     *
     * @return the Netty FullHttpRequest
     */
    public FullHttpRequest getNettyRequest() {
        return nettyRequest;
    }
}
