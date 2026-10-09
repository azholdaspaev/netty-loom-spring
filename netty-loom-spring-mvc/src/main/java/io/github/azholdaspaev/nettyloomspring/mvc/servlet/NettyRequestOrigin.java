package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpScheme;

public record NettyRequestOrigin(HttpScheme scheme, String serverName, int serverPort, String remoteAddr) {

    public static NettyRequestOrigin from(HttpRequest request, HttpConnectionMetadata connection) {
        HttpScheme scheme = connection.httpScheme();
        HostPort host = parseHostHeader(request.headers().get(HttpHeaderNames.HOST));
        if (host != null) {
            return new NettyRequestOrigin(scheme, host.name(), resolvePort(host.port(), scheme), connection.remoteAddr());
        }
        return new NettyRequestOrigin(scheme, bracketIfIpv6(connection.localAddr()),
            resolvePort(connection.localPort(), scheme), connection.remoteAddr());
    }

    public boolean secure() {
        return scheme == HttpScheme.HTTPS;
    }

    public int defaultPort() {
        return scheme.port();
    }

    static int resolvePort(int candidatePort, HttpScheme scheme) {
        return candidatePort > 0 ? candidatePort : scheme.port();
    }

    private static String bracketIfIpv6(String host) {
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            return "[" + host + "]";
        }
        return host;
    }

    record HostPort(String name, int port) {
    }

    static HostPort parseHostHeader(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        host = host.trim();
        int separator = host.startsWith("[") ? host.indexOf(':', host.indexOf(']')) : host.lastIndexOf(':');
        String name = separator < 0 ? host : host.substring(0, separator);
        if (name.isBlank()) {
            return null;
        }
        int port = 0;
        if (separator >= 0) {
            try {
                port = Integer.parseInt(host, separator + 1, host.length(), 10);
            } catch (NumberFormatException notAPort) {
            }
        }
        return new HostPort(name, port);
    }
}
