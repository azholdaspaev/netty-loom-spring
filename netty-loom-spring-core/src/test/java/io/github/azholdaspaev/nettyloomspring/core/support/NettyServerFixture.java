package io.github.azholdaspaev.nettyloomspring.core.support;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.DefaultNettyPipelineConfigurer;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NamedChannelHandler;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyIoHandlerFactory;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerConfiguration;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyTransportPreference;

import java.util.List;

/**
 * Test helper assembling the {@link NettyServer} the core tests exercise, on the platform's
 * auto-selected transport.
 *
 * <p>The registry is a parameter rather than a local because pipelines need it inside their own
 * handlers, and it must be the same instance the server receives: two instances compile, and leave
 * draining watching a different channel set from the one it closes.
 */
public final class NettyServerFixture {

    private NettyServerFixture() {
    }

    public static NettyServer newServer(NettyServerConfiguration configuration,
                                        HttpConnectionRegistry connectionRegistry,
                                        List<NamedChannelHandler> handlers) {
        return new NettyServer(configuration,
            new NettyServerChannelInitializer(new DefaultNettyPipelineConfigurer(handlers), connectionRegistry),
            new NettyIoHandlerFactory(NettyTransportPreference.AUTO),
            connectionRegistry);
    }
}
