package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class ServerPropertiesValidator {

    private final Environment environment;

    ServerPropertiesValidator(Environment environment) {
        this.environment = environment;
    }

    void requireSupported(Ssl ssl, Session session) {
        requireSslNotConfigured(ssl);
        requireSessionPersistenceNotConfigured(session);
    }

    List<String> describeUnsupported() {
        Binder binder = Binder.get(environment);
        List<String> warnings = new ArrayList<>();
        if (binder.bind("server.compression.enabled", Boolean.class).orElse(false)) {
            warnings.add(describe("server.compression.enabled=true", "responses are never compressed (see issue #22)"));
        }
        if (binder.bind("server.http2.enabled", Boolean.class).orElse(false)) {
            warnings.add(describe("server.http2.enabled=true", "only HTTP/1.1 is served (see issue #23)"));
        }
        if (StringUtils.hasText(binder.bind("server.server-header", String.class).orElse(null))) {
            warnings.add(describe("server.server-header", "no Server header is written (see issue #167)"));
        }
        if (binder.bind("server.servlet.register-default-servlet", Boolean.class).orElse(false)) {
            warnings.add(describe("server.servlet.register-default-servlet=true", "no default servlet is registered"));
        }
        Map<String, String> jsp = bindKeys(binder, "server.servlet.jsp");
        jsp.remove("registered", "false");
        if (!jsp.isEmpty()) {
            warnings.add(describe(joinNames("server.servlet.jsp", jsp), "no JSP servlet is registered"));
        }
        Map<String, String> localeCharsets = bindKeys(binder, "server.servlet.encoding.mapping");
        if (!localeCharsets.isEmpty()) {
            warnings.add(describe(joinNames("server.servlet.encoding.mapping", localeCharsets),
                "locale-to-charset mappings are never read"));
        }
        if (binder.bind("server.servlet.session.store-dir", String.class).isBound()) {
            warnings.add(describe("server.servlet.session.store-dir", "sessions are held in memory only"));
        }
        if (binder.bind("server.max-http-request-header-size", String.class).isBound()) {
            warnings.add(describe("server.max-http-request-header-size", "use server.netty.max-header-size"));
        }
        if (!binder.bind("spring.mvc.servlet.path", String.class).orElse("/").equals("/")) {
            warnings.add(describe("spring.mvc.servlet.path", "the DispatcherServlet receives every in-context request"));
        }
        if (binder.bind("server.netty.port", String.class).isBound()) {
            warnings.add(describe("server.netty.port", "it was removed; use server.port"));
        }
        return warnings;
    }

    private static Map<String, String> bindKeys(Binder binder, String prefix) {
        return new TreeMap<>(binder.bind(prefix, Bindable.mapOf(String.class, String.class)).orElse(Map.of()));
    }

    private static String joinNames(String prefix, Map<String, String> keys) {
        return keys.keySet().stream().map(key -> prefix + "." + key).collect(Collectors.joining(", "));
    }

    private static String describe(String properties, String absentBehaviour) {
        return "Ignoring " + properties + ": " + absentBehaviour + ".";
    }

    private void requireSslNotConfigured(Ssl ssl) {
        /*
         * Because this factory is a ConfigurableServletWebServerFactory, Boot binds server.ssl.* onto it,
         * but the Netty pipeline has no SslHandler (issue #16). Fail fast rather than silently serving
         * plaintext while the application looks TLS-configured.
         */
        if (Ssl.isEnabled(ssl)) {
            throw new WebServerException("server.ssl.* is configured but netty-loom-spring does not support "
                + "TLS yet (see issue #16). Remove server.ssl.* or set server.ssl.enabled=false.", null);
        }
    }

    private void requireSessionPersistenceNotConfigured(Session session) {
        /*
         * Under Tomcat this writes SESSIONS.ser and survives a restart. This container has an in-memory
         * store only (issue #13 non-goal), so honouring the property is impossible and ignoring it would
         * silently lose every session on deploy -- the same fail-fast contract as server.ssl.*.
         */
        if (session.isPersistent()) {
            throw new WebServerException("server.servlet.session.persistent=true is configured but "
                + "netty-loom-spring stores sessions in memory only and cannot persist them across "
                + "restarts. Remove the property, or use Spring Session for a durable store.", null);
        }
    }
}
