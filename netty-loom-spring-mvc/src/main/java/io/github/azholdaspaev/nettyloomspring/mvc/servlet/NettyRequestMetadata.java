package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

public record NettyRequestMetadata(String scheme, String serverName, int serverPort,
                                   String remoteAddr, int remotePort) {

    public boolean isSecure() {
        return "https".equals(scheme);
    }

    public int defaultPort() {
        return getDefaultPort(scheme);
    }

    static int getDefaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    static StringBuffer toRequestUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        StringBuffer url = new StringBuffer(scheme).append(':');
        if (host != null && !host.isBlank()) {
            url.append("//").append(host);
            int defaultPort = getDefaultPort(scheme);
            if (request.getServerPort() != defaultPort) {
                url.append(':').append(request.getServerPort());
            }
        }
        return url.append(request.getRequestURI());
    }

    static String resolveRedirect(HttpServletRequest request, String location) {
        URI target = URI.create(location);
        if (target.isAbsolute()) {
            return location;
        }
        String url = toRequestUrl(request).toString();
        if (location.startsWith("?")) {
            return URI.create(url + location).toASCIIString();
        }
        String query = request.getQueryString();
        URI base = URI.create(url + (query == null ? "" : "?" + query));
        return location.isEmpty() ? base.toASCIIString() : base.resolve(target).normalize().toASCIIString();
    }
}
