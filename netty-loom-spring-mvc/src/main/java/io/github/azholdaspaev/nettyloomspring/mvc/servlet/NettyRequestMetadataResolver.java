package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.NetUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class NettyRequestMetadataResolver {

    public static final List<String> DEFAULT_TRUSTED_PROXIES = List.of(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
        "100.64.0.0/10", "127.0.0.0/8", "::1/128", "fe80::/10", "fc00::/7");

    public static final NettyRequestMetadataResolver DIRECT = new NettyRequestMetadataResolver(false, List.of());

    private static final Set<String> FORWARDING_HEADERS = Set.of(
        "forwarded", "x-forwarded-host", "x-forwarded-port", "x-forwarded-proto",
        "x-forwarded-prefix", "x-forwarded-ssl", "x-forwarded-for");

    private final boolean nativeForwarding;
    private final List<Subnet> trustedProxies;

    public NettyRequestMetadataResolver(List<String> trustedProxies) {
        this(true, trustedProxies);
    }

    private NettyRequestMetadataResolver(boolean nativeForwarding, List<String> trustedProxies) {
        this.nativeForwarding = nativeForwarding;
        this.trustedProxies = trustedProxies.stream().map(Subnet::from).toList();
    }

    public static void requireTrustedProxies(List<String> addresses) {
        addresses.forEach(Subnet::from);
    }

    public boolean isNative() {
        return nativeForwarding;
    }

    public boolean isHeaderVisible(String name) {
        return !nativeForwarding || !FORWARDING_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    public NettyRequestMetadata resolve(HttpRequest request, HttpConnectionMetadata connection) {
        NettyRequestMetadata direct = resolveDirect(request, connection);
        if (!nativeForwarding || !isTrusted(connection.remoteAddr())) {
            return direct;
        }
        HttpHeaders headers = request.headers();
        if (headers.contains("Forwarded")) {
            return resolveForwarded(String.join(",", headers.getAll("Forwarded")), direct);
        }
        return resolveXHeaders(headers, direct);
    }

    private static NettyRequestMetadata resolveDirect(HttpRequest request, HttpConnectionMetadata connection) {
        HostPort host = parseDirectHost(request.headers().get(HttpHeaderNames.HOST));
        String name = host == null ? bracketIfIpv6(connection.localAddr()) : host.name();
        int candidate = host == null ? connection.localPort() : host.port();
        int port = candidate > 0 ? candidate : connection.defaultPort();
        return new NettyRequestMetadata(connection.scheme(), name, port, connection.remoteAddr(), connection.remotePort());
    }

    private NettyRequestMetadata resolveForwarded(String value, NettyRequestMetadata direct) {
        List<Map<String, String>> elements = new ForwardedParser(value).parse();
        String address = direct.remoteAddr();
        int port = direct.remotePort();
        Map<String, String> selected = Map.of();
        for (int i = elements.size() - 1; i >= 0 && isTrusted(address); i--) {
            selected = elements.get(i);
            String forValue = selected.get("for");
            if (forValue == null) {
                break;
            }
            Node node = parseNode(forValue, true);
            if (node.address() == null) {
                break;
            }
            address = node.address();
            port = node.port();
        }
        return resolveOrigin(direct, selected.get("proto"), selected.get("host"), null, address, port);
    }

    private NettyRequestMetadata resolveXHeaders(HttpHeaders headers, NettyRequestMetadata direct) {
        String proto = singleValue(headers, "X-Forwarded-Proto");
        String host = singleValue(headers, "X-Forwarded-Host");
        String serverPort = singleValue(headers, "X-Forwarded-Port");
        String address = direct.remoteAddr();
        int port = direct.remotePort();
        if (headers.contains("X-Forwarded-For")) {
            String[] nodes = String.join(",", headers.getAll("X-Forwarded-For")).split(",", -1);
            for (int i = nodes.length - 1; i >= 0 && isTrusted(address); i--) {
                Node node = parseNode(nodes[i].trim(), false);
                if (node.address() == null) {
                    break;
                }
                address = node.address();
                port = node.port();
            }
        }
        return resolveOrigin(direct, proto, host, serverPort, address, port);
    }

    private static NettyRequestMetadata resolveOrigin(NettyRequestMetadata direct, String proto, String host,
                                                       String portValue, String address, int remotePort) {
        String scheme = proto == null ? direct.scheme() : proto.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw newMalformedException();
        }
        HostPort authority = host == null ? null : parseForwardedHost(host);
        String serverName = authority == null ? direct.serverName() : authority.name();
        int port = proto != null || host != null ? NettyRequestMetadata.getDefaultPort(scheme) : direct.serverPort();
        if (authority != null && authority.port() > 0) {
            port = authority.port();
        }
        if (portValue != null) {
            port = parsePort(portValue);
        }
        return new NettyRequestMetadata(scheme, serverName, port, address, remotePort);
    }

    private boolean isTrusted(String address) {
        int zone = address.indexOf('%');
        byte[] bytes = parseIp(zone < 0 ? address : address.substring(0, zone));
        return bytes != null && trustedProxies.stream().anyMatch(subnet -> subnet.contains(bytes));
    }

    private static String singleValue(HttpHeaders headers, String name) {
        List<String> values = headers.getAll(name);
        if (values.isEmpty()) {
            return null;
        }
        String value = values.getFirst().trim();
        if (values.size() != 1 || value.isEmpty() || value.indexOf(',') >= 0) {
            throw newMalformedException();
        }
        return value;
    }

    private static HostPort parseDirectHost(String host) {
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

    private static HostPort parseForwardedHost(String value) {
        String host = value;
        int port = 0;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0 || value.indexOf('%') >= 0 || !NetUtil.isValidIpV6Address(value.substring(1, end))) {
                throw newMalformedException();
            }
            host = value.substring(0, end + 1);
            if (end + 1 < value.length()) {
                if (value.charAt(end + 1) != ':') {
                    throw newMalformedException();
                }
                port = parsePort(value.substring(end + 2));
            }
        } else {
            int separator = value.indexOf(':');
            if (separator >= 0) {
                host = value.substring(0, separator);
                port = parsePort(value.substring(separator + 1));
            }
            if (host.isEmpty() || !host.chars().allMatch(c -> isHostCharacter((char) c))) {
                throw newMalformedException();
            }
        }
        return new HostPort(host, port);
    }

    private static boolean isHostCharacter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
            || "-._~!$&'()*+,;=".indexOf(c) >= 0;
    }

    private static Node parseNode(String value, boolean requireIpv6Brackets) {
        String host = value;
        String port = null;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0 || !NetUtil.isValidIpV6Address(value.substring(1, end))) {
                throw newMalformedException();
            }
            host = value.substring(1, end);
            if (end + 1 < value.length()) {
                if (value.charAt(end + 1) != ':') {
                    throw newMalformedException();
                }
                port = value.substring(end + 2);
            }
        } else if (NetUtil.isValidIpV6Address(value)) {
            if (requireIpv6Brackets) {
                throw newMalformedException();
            }
        } else {
            int separator = value.indexOf(':');
            if (separator >= 0) {
                host = value.substring(0, separator);
                port = value.substring(separator + 1);
            }
        }
        int numericPort = port == null || isObfuscated(port) ? 0 : parsePort(port);
        if ("unknown".equalsIgnoreCase(host) || isObfuscated(host)) {
            return new Node(null, numericPort);
        }
        if (parseIp(host) == null) {
            throw newMalformedException();
        }
        return new Node(host, numericPort);
    }

    private static boolean isObfuscated(String value) {
        return value.startsWith("_") && value.length() > 1
            && value.substring(1).chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-');
    }

    private static int parsePort(String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw newMalformedException();
        }
        try {
            int port = Integer.parseInt(value);
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException invalid) {
        }
        throw newMalformedException();
    }

    private static byte[] parseIp(String value) {
        if (value.indexOf('%') >= 0 || value.startsWith("[")) {
            return null;
        }
        if (!NetUtil.isValidIpV4Address(value) && !NetUtil.isValidIpV6Address(value)) {
            return null;
        }
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(value);
        if (bytes != null && bytes.length == 16) {
            boolean mapped = bytes[10] == (byte) 255 && bytes[11] == (byte) 255;
            for (int i = 0; i < 10; i++) {
                mapped &= bytes[i] == 0;
            }
            if (mapped) {
                return java.util.Arrays.copyOfRange(bytes, 12, 16);
            }
        }
        return bytes;
    }

    private static String bracketIfIpv6(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static IllegalArgumentException newMalformedException() {
        return new IllegalArgumentException("Invalid forwarded headers");
    }

    private record HostPort(String name, int port) { }

    private record Node(String address, int port) { }

    private record Subnet(byte[] address, int prefix) {

        static Subnet from(String value) {
            String[] parts = value.trim().split("/", -1);
            byte[] bytes = parseIp(parts[0]);
            if (bytes == null || parts.length > 2) {
                throw new IllegalArgumentException("Invalid trusted proxy address");
            }
            int prefix = bytes.length * 8;
            if (parts.length == 2) {
                try {
                    if (parts[1].isEmpty() || !parts[1].chars().allMatch(c -> c >= '0' && c <= '9')) {
                        throw new NumberFormatException();
                    }
                    prefix = Integer.parseInt(parts[1]);
                    if (bytes.length == 4 && NetUtil.isValidIpV6Address(parts[0])) {
                        prefix -= 96;
                    }
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("Invalid trusted proxy prefix", invalid);
                }
            }
            if (prefix < 0 || prefix > bytes.length * 8) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix");
            }
            return new Subnet(bytes, prefix);
        }

        boolean contains(byte[] candidate) {
            if (address.length != candidate.length) {
                return false;
            }
            for (int i = 0; i < prefix; i++) {
                int mask = 1 << (7 - i % 8);
                if ((address[i / 8] & mask) != (candidate[i / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class ForwardedParser {

        private final String value;
        private int index;

        private ForwardedParser(String value) {
            this.value = value;
        }

        List<Map<String, String>> parse() {
            List<Map<String, String>> elements = new ArrayList<>();
            while (true) {
                Map<String, String> parameters = new HashMap<>();
                skipWhitespace();
                while (index < value.length() && value.charAt(index) != ',') {
                    if (value.charAt(index) == ';') {
                        index++;
                        skipWhitespace();
                        continue;
                    }
                    String name = readToken().toLowerCase(Locale.ROOT);
                    skipWhitespace();
                    requireCharacter('=');
                    skipWhitespace();
                    String parameter = index < value.length() && value.charAt(index) == '"'
                        ? readQuoted() : readToken();
                    if (parameters.putIfAbsent(name, parameter) != null) {
                        throw newMalformedException();
                    }
                    skipWhitespace();
                    if (index < value.length() && value.charAt(index) != ';' && value.charAt(index) != ',') {
                        throw newMalformedException();
                    }
                }
                elements.add(parameters);
                if (index == value.length()) {
                    return elements;
                }
                index++;
            }
        }

        private String readToken() {
            int start = index;
            while (index < value.length() && isToken(value.charAt(index))) {
                index++;
            }
            if (index == start) {
                throw newMalformedException();
            }
            return value.substring(start, index);
        }

        private String readQuoted() {
            index++;
            StringBuilder result = new StringBuilder();
            while (index < value.length()) {
                char c = value.charAt(index++);
                if (c == '"') {
                    return result.toString();
                }
                if (c == '\\') {
                    if (index == value.length()) {
                        throw newMalformedException();
                    }
                    c = value.charAt(index++);
                }
                if (c < 32 && c != '\t' || c == 127) {
                    throw newMalformedException();
                }
                result.append(c);
            }
            throw newMalformedException();
        }

        private void skipWhitespace() {
            while (index < value.length() && (value.charAt(index) == ' ' || value.charAt(index) == '\t')) {
                index++;
            }
        }

        private void requireCharacter(char expected) {
            if (index == value.length() || value.charAt(index++) != expected) {
                throw newMalformedException();
            }
        }

        private static boolean isToken(char c) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
        }
    }
}
