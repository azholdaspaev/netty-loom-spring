package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyRequestMetadataResolverTest {

    private static final HttpConnectionMetadata CONNECTION =
        new HttpConnectionMetadata("10.0.0.2", 1234, "10.0.0.1", 8080, false, "channel");
    private static final NettyRequestMetadataResolver NATIVE =
        new NettyRequestMetadataResolver(List.of("10.0.0.0/8"));

    @Test
    void shouldResolveDirectOriginWithoutForwarding() {
        var request = request("Forwarded", "broken");
        var metadata = NettyRequestMetadataResolver.DIRECT.resolve(request, CONNECTION);
        assertEquals("http", metadata.scheme());
        assertEquals("internal", metadata.serverName());
        assertEquals(8080, metadata.serverPort());
        assertEquals("10.0.0.2", metadata.remoteAddr());
        assertEquals(1234, metadata.remotePort());
        assertFalse(metadata.isSecure());
    }

    @Test
    void shouldResolveForwardedOriginAndClient() {
        var metadata = NATIVE.resolve(request("Forwarded",
            "for=203.0.113.7;proto=HTTPS;host=app.example"), CONNECTION);
        assertEquals("https", metadata.scheme());
        assertTrue(metadata.isSecure());
        assertEquals("app.example", metadata.serverName());
        assertEquals(443, metadata.serverPort());
        assertEquals(443, metadata.defaultPort());
        assertEquals("203.0.113.7", metadata.remoteAddr());
        assertEquals(0, metadata.remotePort());
    }

    @Test
    void shouldPreferForwardedWithoutMixingHeaderFamilies() {
        var request = request("Forwarded", "for=203.0.113.7");
        request.headers().set("X-Forwarded-Proto", "https");
        request.headers().set("X-Forwarded-Host", "attacker.example");
        var metadata = NATIVE.resolve(request, CONNECTION);
        assertEquals("http", metadata.scheme());
        assertEquals("internal", metadata.serverName());
        assertEquals(8080, metadata.serverPort());
    }

    @Test
    void shouldResolveXHeadersWithExplicitPortPriority() {
        var request = request("X-Forwarded-For", "203.0.113.7, 10.0.0.3");
        request.headers().set("X-Forwarded-Proto", "https");
        request.headers().set("X-Forwarded-Host", "app.example:8443");
        request.headers().set("X-Forwarded-Port", "9443");
        var metadata = NATIVE.resolve(request, CONNECTION);
        assertEquals("203.0.113.7", metadata.remoteAddr());
        assertEquals("app.example", metadata.serverName());
        assertEquals(9443, metadata.serverPort());
    }

    @Test
    void shouldStopBeforeForgedLeftmostHop() {
        var request = request("Forwarded", "for=198.51.100.66;proto=http;host=attacker.example");
        request.headers().add("Forwarded", "for=203.0.113.7;proto=https;host=app.example");
        request.headers().add("Forwarded", "for=10.0.0.3;proto=http;host=internal");
        var metadata = NATIVE.resolve(request, CONNECTION);
        assertEquals("203.0.113.7", metadata.remoteAddr());
        assertEquals("app.example", metadata.serverName());
        assertTrue(metadata.isSecure());
    }

    @Test
    void shouldIgnoreMalformedHeadersFromUntrustedPeer() {
        var resolver = new NettyRequestMetadataResolver(List.of("192.168.0.0/16"));
        assertEquals("10.0.0.2", resolver.resolve(request("Forwarded", "broken"), CONNECTION).remoteAddr());
        assertEquals("http", new NettyRequestMetadataResolver(List.of())
            .resolve(request("X-Forwarded-Proto", "https"), CONNECTION).scheme());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "_hidden"})
    void shouldPreserveLastConcreteHopAfterOpaqueNode(String node) {
        var metadata = NATIVE.resolve(request("Forwarded",
            "for=198.51.100.66;host=attacker.example, for=" + node
                + ";proto=https;host=app.example, for=10.0.0.3"), CONNECTION);
        assertEquals("10.0.0.3", metadata.remoteAddr());
        assertEquals("app.example", metadata.serverName());
        assertTrue(metadata.isSecure());
    }

    @Test
    void shouldRetainNumericAddressWithOpaquePort() {
        var metadata = NATIVE.resolve(request("Forwarded",
            "for=\"203.0.113.7:_hidden\";host=app.example"), CONNECTION);
        assertEquals("203.0.113.7", metadata.remoteAddr());
        assertEquals(0, metadata.remotePort());
    }

    @Test
    void shouldParseQuotedIpv6AndUnknownExtension() {
        var resolver = new NettyRequestMetadataResolver(List.of("::1/128"));
        var connection = new HttpConnectionMetadata("::1", 1234, "::1", 8080, false, "channel");
        var metadata = resolver.resolve(request("Forwarded",
            "For=\"[2001:db8::7]:4711\";Proto=https;Host=\"[2001:db8::9]:8443\";ext=\"a,b;\\\"c\""), connection);
        assertEquals("2001:db8::7", metadata.remoteAddr());
        assertEquals(4711, metadata.remotePort());
        assertEquals("[2001:db8::9]", metadata.serverName());
        assertEquals(8443, metadata.serverPort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",", "for=203.0.113.7,"})
    void shouldStopOnEmptyElementWithoutXFallback(String value) {
        var request = request("Forwarded", value);
        request.headers().set("X-Forwarded-Proto", "https");
        var metadata = NATIVE.resolve(request, CONNECTION);
        assertEquals("http", metadata.scheme());
        assertEquals("10.0.0.2", metadata.remoteAddr());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "broken", "proto=https;PROTO=http", "proto=javascript", "host=app.example:0",
        "host=app.example:65536", "host=\"user@app.example\"", "host=\"app.example/path\"",
        "host=\"app.example?x\"", "host=\"app example\"", "host=\"app\\\\example\"",
        "host=\"[2001:db8::1\"", "for=not-an-ip", "for=\"[2001:db8::1]:bad\"", "proto=\"https"
    })
    void shouldRejectMalformedTrustedForwarded(String value) {
        assertThrows(IllegalArgumentException.class,
            () -> NATIVE.resolve(request("Forwarded", value), CONNECTION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-Port"})
    void shouldRejectAmbiguousXOriginLists(String name) {
        assertThrows(IllegalArgumentException.class,
            () -> NATIVE.resolve(request(name, "one,two"), CONNECTION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"host.example", "10.0.0.0/33", "::1/129", "-1.0.0.0/8", ""})
    void shouldRejectInvalidTrustedNetwork(String value) {
        assertThrows(IllegalArgumentException.class,
            () -> new NettyRequestMetadataResolver(List.of(value)));
    }

    @ParameterizedTest
    @CsvSource({
        "10.1.2.3/24, 10.1.2.255, true", "10.1.2.3/24, 10.1.3.0, false",
        "172.16.0.0/12, 172.31.255.255, true", "172.16.0.0/12, 172.32.0.0, false",
        "2001:db8::/32, 2001:db8:ffff::1, true", "2001:db8::/32, 2001:db9::1, false",
        "fc00::/7, fdff::1, true", "fc00::/7, fe00::1, false",
        "10.0.0.2, 10.0.0.2, true", "10.0.0.2, 10.0.0.3, false",
        "10.0.0.0/8, ::ffff:10.0.0.2, true", "::ffff:10.0.0.2/128, 10.0.0.2, true",
        "::ffff:10.0.0.0/104, 10.1.2.3, true", "fe80::/10, fe80::1%en0, true",
        "0.0.0.0/0, 203.0.113.7, true", "::/0, 2001:db8::1, true"
    })
    void shouldMatchNumericIpAndCidrBoundaries(String subnet, String peer, boolean trusted) {
        var resolver = new NettyRequestMetadataResolver(List.of(subnet));
        var connection = new HttpConnectionMetadata(peer, 1234, "10.0.0.1", 8080, false, "channel");
        var metadata = resolver.resolve(request("X-Forwarded-Proto", "https"), connection);
        assertEquals(trusted, metadata.isSecure());
    }

    @ParameterizedTest
    @CsvSource({"10.255.255.255, true", "172.31.255.255, true", "192.168.1.2, true",
        "169.254.1.2, true", "100.127.255.255, true", "127.0.0.2, true", "::1, true",
        "febf::1, true", "fdff::1, true", "100.128.0.1, false", "172.32.0.1, false",
        "2001:db8::1, false", "203.0.113.7, false"})
    void shouldApplyDefaultProxyNetworks(String peer, boolean trusted) {
        var resolver = new NettyRequestMetadataResolver(NettyRequestMetadataResolver.DEFAULT_TRUSTED_PROXIES);
        var connection = new HttpConnectionMetadata(peer, 1234, "10.0.0.1", 8080, false, "channel");
        assertEquals(trusted, resolver.resolve(request("X-Forwarded-Proto", "https"), connection).isSecure());
    }

    @ParameterizedTest
    @ValueSource(strings = {"for=\"[fe80::1%en0]\"", "host=\"[fe80::1%en0]\"",
        "for=\"[203.0.113.7]\"", "for=\"2001:db8::1\""})
    void shouldRejectNonRfcIpv6NodesAndAuthorities(String value) {
        assertThrows(IllegalArgumentException.class,
            () -> NATIVE.resolve(request("Forwarded", value), CONNECTION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-Port"})
    void shouldRejectRepeatedXOriginFields(String name) {
        var request = request(name, "443");
        request.headers().add(name, "443");
        assertThrows(IllegalArgumentException.class, () -> NATIVE.resolve(request, CONNECTION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Forwarded", "x-forwarded-proto", "X-Forwarded-Ssl", "X-Forwarded-Prefix"})
    void shouldHideSpringForwardingHeadersOnlyInNative(String name) {
        assertFalse(NATIVE.isHeaderVisible(name));
        assertTrue(NettyRequestMetadataResolver.DIRECT.isHeaderVisible(name));
        assertTrue(NATIVE.isHeaderVisible("Host"));
    }

    private static DefaultHttpRequest request(String name, String value) {
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        request.headers().set("Host", "internal:8080");
        request.headers().set(name, value);
        return request;
    }
}
