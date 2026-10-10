package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;
import org.springframework.core.Ordered;

/**
 * A customizer rather than a setting read in the factory bean method, so that a factory the
 * application declares itself honours the strategy too. Order 0, as Boot's
 * {@code TomcatWebServerFactoryCustomizer}: an unordered application customizer then runs after this
 * one and its {@code setUseForwardHeaders} wins.
 */
public class NettyForwardHeadersCustomizer implements WebServerFactoryCustomizer<NettyServletWebServerFactory>, Ordered {

    private final ServerProperties serverProperties;

    public NettyForwardHeadersCustomizer(ServerProperties serverProperties) {
        this.serverProperties = serverProperties;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public void customize(NettyServletWebServerFactory factory) {
        factory.setUseForwardHeaders(serverProperties.getForwardHeadersStrategy() == ForwardHeadersStrategy.NATIVE);
    }
}
