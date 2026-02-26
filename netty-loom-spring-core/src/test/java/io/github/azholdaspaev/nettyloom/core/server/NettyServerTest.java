package io.github.azholdaspaev.nettyloom.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.azholdaspaev.nettyloom.core.http.DefaultNettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.pipeline.HttpServerNettyPipelineConfigurer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NettyServerTest {

    private static final int UNBOUND_CONFIG_PORT = 9999;

    private NettyServer server;

    @AfterEach
    void tearDown() {
        if (server != null && server.getState() == NettyServerState.RUNNING) {
            server.stop();
        }
    }

    @Test
    void shouldHaveCreatedStateAfterConstruction() {
        // Given
        server = createServer(0);

        // When
        NettyServerState state = server.getState();

        // Then
        assertThat(state).isEqualTo(NettyServerState.CREATED);
    }

    @Test
    void shouldTransitionToRunningWhenStarted() {
        // Given
        server = createServer(0);

        // When
        server.start();

        // Then
        assertThat(server.getState()).isEqualTo(NettyServerState.RUNNING);
    }

    @Test
    void shouldTransitionToStoppedWhenStopped() {
        // Given
        server = createServer(0);
        server.start();

        // When
        server.stop();

        // Then
        assertThat(server.getState()).isEqualTo(NettyServerState.STOPPED);
    }

    @Test
    void shouldThrowWhenStartedTwice() {
        // Given
        server = createServer(0);
        server.start();

        // When / Then
        assertThatThrownBy(() -> server.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot start server");
    }

    @Test
    void shouldThrowWhenStoppedWithoutStarting() {
        // Given
        server = createServer(0);

        // When / Then
        assertThatThrownBy(() -> server.stop())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot stop server");
    }

    @Test
    void shouldReturnActualPortWhenBoundToEphemeralPort() {
        // Given
        server = createServer(0);

        // When
        server.start();

        // Then
        assertThat(server.getPort()).isGreaterThan(0);
    }

    @Test
    void shouldReturnConfiguredPortWhenNotStarted() {
        // Given
        server = createServer(UNBOUND_CONFIG_PORT);

        // When
        int port = server.getPort();

        // Then
        assertThat(port).isEqualTo(UNBOUND_CONFIG_PORT);
    }

    @Test
    void shouldReturnResponseForHttpRequest() throws Exception {
        // Given
        server = createHttpServer(0);
        server.start();

        int port = server.getPort();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/info"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        // When
        HttpResponse<String> httpResponse =
                HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());

        // Then
        assertThat(httpResponse.statusCode()).isEqualTo(200);
    }

    @Test
    void shouldSetStateToStoppedWhenStartFailsDueToPortConflict() throws Exception {
        // Given
        try (ServerSocket occupied = new ServerSocket(0)) {
            int occupiedPort = occupied.getLocalPort();
            server = createServer(occupiedPort);

            // When / Then
            assertThatThrownBy(() -> server.start()).isInstanceOf(IllegalStateException.class);
            assertThat(server.getState()).isEqualTo(NettyServerState.STOPPED);
        }
    }

    private NettyServer createServer(int port) {
        NettyServerConfig config = NettyServerConfig.builder()
                .port(port)
                .bossThreads(1)
                .workerThreads(1)
                .build();
        NettyServerInitializer initializer = new NettyServerInitializer(_ -> {});
        return new NettyServer(config, initializer);
    }

    private NettyServer createHttpServer(int port) {
        NettyServerConfig config = NettyServerConfig.builder()
                .port(port)
                .bossThreads(1)
                .workerThreads(1)
                .maxInitialLineLength(4096)
                .maxHeaderSize(8192)
                .maxChunkSize(8192)
                .maxContentLength(1048576)
                .idleTimeout(Duration.ofSeconds(30))
                .build();

        NettyServerInitializer initializer = new NettyServerInitializer(new HttpServerNettyPipelineConfigurer(
                config,
                _ -> DefaultNettyHttpResponse.builder().build(),
                (ex, request) -> DefaultNettyHttpResponse.builder().build()));
        return new NettyServer(config, initializer);
    }
}
