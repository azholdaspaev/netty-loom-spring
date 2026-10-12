package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.core.env.Environment;

final class ServerPropertiesValidator {

    private final Environment environment;

    ServerPropertiesValidator(Environment environment) {
        this.environment = environment;
    }

    void requireSupported(Ssl ssl, Session session) {
        requireSslNotConfigured(ssl);
        requireSessionPersistenceNotConfigured(session);
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
