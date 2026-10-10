package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyForwardedHeaders.FORWARDED;
import static io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyForwardedHeaders.X_FORWARDED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyForwardedHeaderResolverTest {

    private static final String PROXY = "10.0.0.1";
    private static final String CLIENT = "198.51.100.1";

    private static HttpRequest request(String... headers) {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x");
        request.headers().set(HttpHeaderNames.HOST, "internal:8080");
        for (int i = 0; i < headers.length; i += 2) {
            request.headers().add(headers[i], headers[i + 1]);
        }
        return request;
    }

    private static HttpConnectionMetadata connectionFrom(String peer) {
        return new HttpConnectionMetadata(peer, 40000, "10.0.0.9", 8080, false, "");
    }

    private static NettyRequestOrigin resolve(NettyForwardedHeaders forwardedHeaders, String peer, String... headers) {
        return new NettyForwardedHeaderResolver(forwardedHeaders, List.of("10.0.0.0/8", "fc00::/7"))
            .resolve(request(headers), connectionFrom(peer));
    }

    private static NettyRequestOrigin direct(String peer, String... headers) {
        return NettyRequestOrigin.from(request(headers), connectionFrom(peer));
    }

    @Test
    void shouldKeepDirectOriginForUntrustedPeer() {
        String[] headers = {"X-Forwarded-For", CLIENT, "X-Forwarded-Proto", "https"};

        assertEquals(direct("203.0.113.9", headers), resolve(X_FORWARDED, "203.0.113.9", headers));
    }

    @ParameterizedTest
    @CsvSource({
        "10.0.0.0, true", "10.255.255.255, true", "9.255.255.255, false", "11.0.0.0, false",
        "fc00::1, true", "fdff:ffff::1, true", "fbff::1, false", "fe00::1, false", "ff02::1, false"
    })
    void shouldTrustPeerOnlyInsideConfiguredRanges(String peer, boolean trusted) {
        var origin = resolve(X_FORWARDED, peer, "X-Forwarded-For", CLIENT);

        assertEquals(trusted ? CLIENT : peer, origin.remoteAddr(), "peer " + peer);
    }

    @Test
    void shouldTrustSingleAddressWithoutPrefix() {
        var resolver = new NettyForwardedHeaderResolver(X_FORWARDED, List.of("192.0.2.7"));

        assertEquals(CLIENT, resolver.resolve(request("X-Forwarded-For", CLIENT), connectionFrom("192.0.2.7")).remoteAddr());
        assertEquals("192.0.2.8", resolver.resolve(request("X-Forwarded-For", CLIENT), connectionFrom("192.0.2.8")).remoteAddr());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-ip", "10.0.0.0/", "10.0.0.0/33", "10.0.0.0/-1", "::1/129", "10.0.0.0/8/8", "app.example"})
    void shouldRejectInvalidInternalProxy(String proxy) {
        assertThrows(IllegalArgumentException.class, () -> new NettyForwardedHeaderResolver(X_FORWARDED, List.of(proxy)),
            "an internal proxy must be an IP literal or a CIDR block: " + proxy);
    }

    @Test
    void shouldSkipSpoofedLeftmostForwardedFor() {
        var origin = resolve(X_FORWARDED, PROXY, "X-Forwarded-For", "6.6.6.6, " + CLIENT + ", 10.0.0.2");

        assertEquals(CLIENT, origin.remoteAddr());
    }

    @Test
    void shouldUseLeftmostNodeWhenEveryNodeIsTrusted() {
        assertEquals("10.0.0.3", resolve(X_FORWARDED, PROXY, "X-Forwarded-For", "10.0.0.3, 10.0.0.2").remoteAddr());
    }

    @Test
    void shouldStopWalkAtLastTrustedHopOnEmptyNode() {
        assertEquals("10.0.0.2", resolve(X_FORWARDED, PROXY, "X-Forwarded-For", CLIENT + ", , 10.0.0.2").remoteAddr());
    }

    @Test
    void shouldJoinForwardedForHeaderLines() {
        var origin = resolve(X_FORWARDED, PROXY, "X-Forwarded-For", "6.6.6.6, " + CLIENT, "X-Forwarded-For", "10.0.0.2");

        assertEquals(CLIENT, origin.remoteAddr());
    }

    @Test
    void shouldTrustIpv4MappedNodeInsideIpv4Range() {
        assertEquals(CLIENT, resolve(X_FORWARDED, PROXY, "X-Forwarded-For", CLIENT + ", ::ffff:10.0.0.2").remoteAddr());
    }

    @Test
    void shouldTrustScopedLinkLocalPeer() {
        var resolver = new NettyForwardedHeaderResolver(X_FORWARDED, List.of("fe80::/10"));

        var origin = resolver.resolve(request("X-Forwarded-For", CLIENT), connectionFrom("fe80:0:0:0:0:0:0:1%en0"));

        assertEquals(CLIENT, origin.remoteAddr(), "a scope id on the socket peer must not hide it from fe80::/10");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "198.51.100.1:5555 | 198.51.100.1",
        "[2001:db8::1]:4711 | 2001:db8::1",
        "2001:db8::1 | 2001:db8::1",
        "unknown | unknown"
    })
    void shouldReportClientNodeWithoutPort(String node, String client) {
        assertEquals(client, resolve(X_FORWARDED, PROXY, "X-Forwarded-For", node).remoteAddr());
    }

    @Test
    void shouldMakeOriginSecureOnForwardedHttps() {
        var origin = resolve(X_FORWARDED, PROXY, "X-Forwarded-Proto", "https");

        assertEquals(new NettyRequestOrigin(HttpScheme.HTTPS, "internal", 443, PROXY), origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https, http", "http", "HTTP"})
    void shouldStayInsecureUnlessEveryForwardedProtoIsHttps(String proto) {
        var origin = resolve(X_FORWARDED, PROXY, "X-Forwarded-Proto", proto);

        assertEquals(new NettyRequestOrigin(HttpScheme.HTTP, "internal", 80, PROXY), origin, "proto " + proto);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp", "", "https;x"})
    void shouldIgnoreUnrecognisedForwardedProto(String proto) {
        assertEquals(direct(PROXY), resolve(X_FORWARDED, PROXY, "X-Forwarded-Proto", proto), "proto " + proto);
    }

    @Test
    void shouldTakePortFromForwardedPortWithoutProto() {
        var origin = resolve(X_FORWARDED, PROXY, "X-Forwarded-Port", "8443");

        assertEquals(new NettyRequestOrigin(HttpScheme.HTTP, "internal", 8443, PROXY), origin);
    }

    @Test
    void shouldPreferForwardedPortOverForwardedHostPort() {
        var origin = resolve(X_FORWARDED, PROXY, "X-Forwarded-Host", "app.example:9000", "X-Forwarded-Port", "8443");

        assertEquals(8443, origin.serverPort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "0", "65536", "-1", "443, 8443"})
    void shouldIgnoreInvalidForwardedPort(String port) {
        assertEquals(direct(PROXY), resolve(X_FORWARDED, PROXY, "X-Forwarded-Port", port), "port " + port);
    }

    @Test
    void shouldTakeServerFromForwardedHostWithItsPort() {
        var origin = resolve(X_FORWARDED, PROXY, "X-Forwarded-Host", "app.example:8443", "X-Forwarded-Proto", "https");

        assertEquals(new NettyRequestOrigin(HttpScheme.HTTPS, "app.example", 8443, PROXY), origin);
    }

    @Test
    void shouldUseSchemeDefaultPortForForwardedHostWithoutPort() {
        var origin = resolve(X_FORWARDED, PROXY, "X-Forwarded-Host", "app.example");

        assertEquals(new NettyRequestOrigin(HttpScheme.HTTP, "app.example", 80, PROXY), origin);
    }

    @Test
    void shouldIgnoreForwardedHostWithSeveralValues() {
        assertEquals(direct(PROXY), resolve(X_FORWARDED, PROXY, "X-Forwarded-Host", "a.example, b.example"));
        assertEquals(direct(PROXY), resolve(X_FORWARDED, PROXY, "X-Forwarded-Host", "a.example", "X-Forwarded-Host", "b.example"));
    }

    @Test
    void shouldIgnoreForwardedHeaderUnderXForwardedFamily() {
        String[] headers = {"Forwarded", "for=" + CLIENT + ";proto=https;host=app.example"};

        assertEquals(direct(PROXY, headers), resolve(X_FORWARDED, PROXY, headers));
    }

    @Test
    void shouldTakeProtoAndHostFromBoundaryElement() {
        var origin = resolve(FORWARDED, PROXY,
            "Forwarded", "for=6.6.6.6;proto=http;host=evil.example, for=" + CLIENT + ";proto=https;host=app.example",
            "Forwarded", "for=10.0.0.2;proto=http;host=lb.internal");

        assertEquals(new NettyRequestOrigin(HttpScheme.HTTPS, "app.example", 443, CLIENT), origin);
    }

    @Test
    void shouldReadForwardedParametersCaseInsensitivelyAndQuoted() {
        var origin = resolve(FORWARDED, PROXY, "Forwarded", "For=\"[2001:db8::1]:4711\"; Proto=HTTPS; Host=\"app.example:8443\"");

        assertEquals(new NettyRequestOrigin(HttpScheme.HTTPS, "app.example", 8443, "2001:db8::1"), origin);
    }

    @Test
    void shouldStopForwardedWalkAtObfuscatedNode() {
        var origin = resolve(FORWARDED, PROXY, "Forwarded", "for=" + CLIENT + ", for=unknown;proto=https");

        assertEquals(new NettyRequestOrigin(HttpScheme.HTTPS, "internal", 443, "unknown"), origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"proto=https", "for=\"\";proto=https", "for=;proto=https"})
    void shouldKeepLastTrustedHopWhenForIsEmptyOrMissing(String element) {
        var origin = resolve(FORWARDED, PROXY, "Forwarded", "for=" + CLIENT + ", " + element);

        assertEquals(new NettyRequestOrigin(HttpScheme.HTTPS, "internal", 443, PROXY), origin, element);
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "for=\"unterminated", ";;,,", "=", "for", "for=" + "\"\\"})
    void shouldIgnoreMalformedForwardedHeader(String forwarded) {
        var origin = assertDoesNotThrow(() -> resolve(FORWARDED, PROXY, "Forwarded", forwarded));

        assertEquals(direct(PROXY), origin, forwarded);
    }

    @Test
    void shouldIgnoreXForwardedHeadersUnderForwardedFamily() {
        String[] headers = {"X-Forwarded-For", CLIENT, "X-Forwarded-Proto", "https", "X-Forwarded-Port", "8443"};

        assertEquals(direct(PROXY, headers), resolve(FORWARDED, PROXY, headers));
    }
}
