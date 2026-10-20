package io.github.azholdaspaev.nettyloomspring.integration.app;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * The wrong key carries the signing key's id rather than its own, because a token under an unknown
 * id fails on key lookup, not on the signature check under test.
 */
public class JwkSetServer implements AutoCloseable {

    private static final String KEY_ID = "integration-test";

    private final RSAKey signingKey;

    private final RSAKey wrongKey;

    private final HttpServer server;

    public JwkSetServer() throws IOException, JOSEException {
        this.signingKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        this.wrongKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        byte[] jwkSet = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jwks.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwkSet.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(jwkSet);
            }
        });
        server.start();
    }

    public URI getJwkSetUri() {
        InetSocketAddress address = server.getAddress();
        return URI.create("http://" + address.getHostString() + ":" + address.getPort() + "/jwks.json");
    }

    public String newToken(JwtClaimsSet claims) {
        return sign(signingKey, claims);
    }

    public String newTokenSignedByWrongKey(JwtClaimsSet claims) {
        return sign(wrongKey, claims);
    }

    private static String sign(RSAKey key, JwtClaimsSet claims) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
