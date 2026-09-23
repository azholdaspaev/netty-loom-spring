package io.github.azholdaspaev.nettyloomspring.core.support;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionRegistry;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpDrainHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpPipeliningHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestBodyLimitHandler;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestHandler;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineDefinition;
import io.github.azholdaspaev.nettyloomspring.core.pipeline.NettyPipelineStep;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyIoHandlerFactory;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerChannelInitializer;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyServerConfiguration;
import io.github.azholdaspaev.nettyloomspring.core.server.NettyTransportPreference;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Test helper assembling the {@link NettyServer} the core tests exercise, on the platform's
 * auto-selected transport unless a test passes its own {@link NettyIoHandlerFactory}.
 *
 * <p>The registry is a parameter rather than a local because pipelines need it inside their own
 * handlers, and it must be the same instance the server receives: two instances compile, and leave
 * draining watching a different channel set from the one it closes.
 */
public final class NettyServerFixture {

    private static final int MAX_HTTP_REQUEST_BODY_BYTES = 1024 * 1024;

    private static final Duration UNREACHED_WRITE_STALL_TIMEOUT = Duration.ofSeconds(60);

    private NettyServerFixture() {
    }

    /**
     * The request-carrying steps of the HTTP pipeline, without its timeout and failure handlers, on
     * a loopback port the OS picks; handler order follows {@code NettyLoomAutoConfiguration}, which
     * owns the reasons for it. The registry is a parameter, though this method builds the handlers
     * itself, so a test can pass a subclass that hooks {@code abortDrain}.
     */
    public static NettyServer newHttpServer(HttpConnectionRegistry connectionRegistry,
                                            HttpRequestDispatcher dispatcher,
                                            ExecutorService dispatchExecutor) {
        NettyServerConfiguration configuration = new NettyServerConfiguration(
            0, InetAddress.getLoopbackAddress(), 0, 0, false, 128);
        return newServer(configuration, connectionRegistry, List.of(
            new NettyPipelineStep("httpCodec", HttpServerCodec::new),
            new NettyPipelineStep("httpKeepAlive", HttpServerKeepAliveHandler::new),
            new NettyPipelineStep("drain", () -> new HttpDrainHandler(connectionRegistry)),
            new NettyPipelineStep("pipelining", HttpPipeliningHandler::new),
            new NettyPipelineStep("bodyLimit", () -> new HttpRequestBodyLimitHandler(MAX_HTTP_REQUEST_BODY_BYTES)),
            new NettyPipelineStep("dispatcher",
                () -> new HttpRequestHandler(dispatcher, dispatchExecutor, connectionRegistry, UNREACHED_WRITE_STALL_TIMEOUT))));
    }

    public static NettyServer newServer(NettyServerConfiguration configuration,
                                        HttpConnectionRegistry connectionRegistry,
                                        List<NettyPipelineStep> handlers) {
        return newServer(configuration, connectionRegistry, handlers,
            new NettyIoHandlerFactory(NettyTransportPreference.AUTO));
    }

    public static NettyServer newServer(NettyServerConfiguration configuration,
                                        HttpConnectionRegistry connectionRegistry,
                                        List<NettyPipelineStep> handlers,
                                        NettyIoHandlerFactory ioHandlerFactory) {
        return new NettyServer(configuration,
            new NettyServerChannelInitializer(new NettyPipelineDefinition(handlers), connectionRegistry),
            ioHandlerFactory,
            connectionRegistry);
    }
}
