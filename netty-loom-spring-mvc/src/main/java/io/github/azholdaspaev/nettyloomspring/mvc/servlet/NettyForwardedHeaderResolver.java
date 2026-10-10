package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.util.AsciiString;
import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The origin a trusted proxy forwarded: Tomcat 11's {@code RemoteIpValve} for the {@code X-Forwarded-*}
 * family, RFC 7239 for {@code Forwarded}. Only the family the operator names is read, rather than either
 * one that is present, because a proxy passes the family it does not write through untouched, and a
 * client could then choose its own origin. The walk, the port precedence and the deviations from Tomcat
 * are in {@code docs/configuration.md} § Forwarded headers.
 */
public final class NettyForwardedHeaderResolver implements NettyRequestOriginResolver {

    private static final AsciiString FORWARDED = AsciiString.cached("forwarded");
    private static final AsciiString X_FORWARDED_FOR = AsciiString.cached("x-forwarded-for");
    private static final AsciiString X_FORWARDED_PROTO = AsciiString.cached("x-forwarded-proto");
    private static final AsciiString X_FORWARDED_HOST = AsciiString.cached("x-forwarded-host");
    private static final AsciiString X_FORWARDED_PORT = AsciiString.cached("x-forwarded-port");
    private static final int MAX_PORT = 65535;

    private final NettyForwardedHeaders forwardedHeaders;
    private final List<AddressRange> internalProxies;

    public NettyForwardedHeaderResolver(NettyForwardedHeaders forwardedHeaders, List<String> internalProxies) {
        this.forwardedHeaders = forwardedHeaders;
        this.internalProxies = internalProxies.stream().map(AddressRange::parse).toList();
    }

    @Override
    public NettyRequestOrigin resolve(HttpRequest request, HttpConnectionMetadata connection) {
        NettyRequestOrigin direct = NettyRequestOrigin.from(request, connection);
        if (!isInternalProxy(connection.remoteAddr())) {
            return direct;
        }
        Forwarding forwarding = forwardedHeaders == NettyForwardedHeaders.FORWARDED
            ? readForwarded(request.headers(), connection.remoteAddr())
            : readXForwarded(request.headers(), connection.remoteAddr());
        return forwarding.applyTo(direct);
    }

    private Forwarding readXForwarded(HttpHeaders headers, String peer) {
        Hop hop = walkToClient(Arrays.asList(String.join(",", headers.getAll(X_FORWARDED_FOR)).split(",", -1)), peer);
        return new Forwarding(hop.remoteAddr(), parseProtos(headers.getAll(X_FORWARDED_PROTO)),
            findSingleValue(headers.getAll(X_FORWARDED_HOST)), parsePort(findSingleValue(headers.getAll(X_FORWARDED_PORT))));
    }

    private Forwarding readForwarded(HttpHeaders headers, String peer) {
        List<Map<String, String>> elements = parseForwarded(String.join(",", headers.getAll(FORWARDED)));
        Hop hop = walkToClient(elements.stream().map(element -> element.getOrDefault("for", "")).toList(), peer);
        Map<String, String> element = elements.get(hop.index());
        return new Forwarding(hop.remoteAddr(), parseProto(element.get("proto")), element.get("host"), 0);
    }

    private Hop walkToClient(List<String> nodes, String peer) {
        String remoteAddr = peer;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            String node = stripPort(nodes.get(i).trim());
            if (node.isEmpty()) {
                return new Hop(i, remoteAddr);
            }
            remoteAddr = node;
            if (i == 0 || !isInternalProxy(node)) {
                return new Hop(i, remoteAddr);
            }
        }
        return new Hop(0, remoteAddr);
    }

    private boolean isInternalProxy(String address) {
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(address);
        if (bytes == null) {
            return false;
        }
        // InetAddress folds ::ffff:a.b.c.d to IPv4, which is how Tomcat's NetMaskSet matches it against 10.0.0.0/8.
        byte[] folded = foldIpv4Mapped(bytes);
        return internalProxies.stream().anyMatch(range -> range.matches(folded));
    }

    private static byte[] foldIpv4Mapped(byte[] address) {
        try {
            return InetAddress.getByAddress(address).getAddress();
        } catch (UnknownHostException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private static String stripPort(String node) {
        if (node.startsWith("[")) {
            int close = node.indexOf(']');
            return close < 0 ? node : node.substring(1, close);
        }
        int colon = node.indexOf(':');
        return colon >= 0 && colon == node.lastIndexOf(':') ? node.substring(0, colon) : node;
    }

    private static HttpScheme parseProtos(List<String> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        HttpScheme scheme = HttpScheme.HTTPS;
        for (String value : String.join(",", lines).split(",", -1)) {
            HttpScheme parsed = parseProto(value.trim());
            if (parsed == null) {
                return null;
            }
            if (parsed == HttpScheme.HTTP) {
                scheme = HttpScheme.HTTP;
            }
        }
        return scheme;
    }

    private static HttpScheme parseProto(String value) {
        if (HttpScheme.HTTPS.toString().equalsIgnoreCase(value)) {
            return HttpScheme.HTTPS;
        }
        return HttpScheme.HTTP.toString().equalsIgnoreCase(value) ? HttpScheme.HTTP : null;
    }

    private static String findSingleValue(List<String> lines) {
        if (lines.size() != 1 || lines.getFirst().indexOf(',') >= 0 || lines.getFirst().isBlank()) {
            return null;
        }
        return lines.getFirst().trim();
    }

    private static int parsePort(String value) {
        if (value == null) {
            return 0;
        }
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= MAX_PORT ? port : 0;
        } catch (NumberFormatException notAPort) {
            return 0;
        }
    }

    private static List<Map<String, String>> parseForwarded(String value) {
        List<Map<String, String>> elements = new ArrayList<>();
        for (String element : splitUnquoted(value, ',')) {
            Map<String, String> parameters = new HashMap<>();
            for (String pair : splitUnquoted(element, ';')) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    parameters.putIfAbsent(pair.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                        unquote(pair.substring(equals + 1).trim()));
                }
            }
            elements.add(parameters);
        }
        return elements;
    }

    private static List<String> splitUnquoted(String value, char separator) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted && c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == separator && !quoted) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    private static String unquote(String value) {
        if (!value.startsWith("\"")) {
            return value;
        }
        if (value.length() < 2 || !value.endsWith("\"") || value.endsWith("\\\"")) {
            return "";
        }
        StringBuilder unquoted = new StringBuilder(value.length());
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() - 1) {
                c = value.charAt(++i);
            }
            unquoted.append(c);
        }
        return unquoted.toString();
    }

    private record Hop(int index, String remoteAddr) {
    }

    private record Forwarding(String remoteAddr, HttpScheme scheme, String host, int port) {

        NettyRequestOrigin applyTo(NettyRequestOrigin direct) {
            HttpScheme effective = scheme != null ? scheme : direct.scheme();
            NettyRequestOrigin.HostPort forwardedHost = NettyRequestOrigin.parseHostHeader(host);
            String serverName = forwardedHost != null ? forwardedHost.name() : direct.serverName();
            int serverPort;
            if (port > 0) {
                serverPort = port;
            } else if (forwardedHost != null) {
                serverPort = NettyRequestOrigin.resolvePort(forwardedHost.port(), effective);
            } else if (scheme != null) {
                serverPort = effective.port();
            } else {
                serverPort = direct.serverPort();
            }
            return new NettyRequestOrigin(effective, serverName, serverPort, remoteAddr);
        }
    }

    /**
     * A prefix compare of our own rather than Netty's {@code IpSubnetFilterRule}, because its
     * {@code Ip6SubnetFilterRule.matches} also accepts any address whose masked bits equal the mask.
     */
    private record AddressRange(byte[] network, int prefixLength) {

        static AddressRange parse(String range) {
            int slash = range.indexOf('/');
            String address = slash < 0 ? range : range.substring(0, slash);
            byte[] network = NetUtil.createByteArrayFromIpAddressString(address);
            if (network == null) {
                throw new IllegalArgumentException("Not an IP address or CIDR block: '" + range + "'");
            }
            int bits = network.length * Byte.SIZE;
            int prefixLength;
            try {
                prefixLength = slash < 0 ? bits : Integer.parseInt(range.substring(slash + 1));
            } catch (NumberFormatException notAPrefix) {
                throw new IllegalArgumentException("Not an IP address or CIDR block: '" + range + "'", notAPrefix);
            }
            if (prefixLength < 0 || prefixLength > bits) {
                throw new IllegalArgumentException("Prefix length out of range in '" + range + "'");
            }
            return new AddressRange(network, prefixLength);
        }

        boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / Byte.SIZE;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (Byte.SIZE - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
