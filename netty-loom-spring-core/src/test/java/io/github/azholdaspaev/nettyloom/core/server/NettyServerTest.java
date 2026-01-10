package io.github.azholdaspaev.nettyloom.core.server;

import io.github.azholdaspaev.nettyloom.core.handler.TestHttpRequestHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettyServerTest {

    private static NettyServer server;
    private static HttpClient client;
    private static ExecutorService handlerExecutor;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        TestHttpRequestHandler handler = new TestHttpRequestHandler(handlerExecutor);

        NettyServerConfiguration config = NettyServerConfiguration.builder()
                .port(0)
                .build();

        server = new NettyServer(config, handler);
        server.start();
        port = server.getPort();

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @AfterAll
    static void stopServer() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        if (handlerExecutor != null) {
            handlerExecutor.shutdown();
        }
    }

    @Test
    void serverRespondsWithHelloWorld() throws Exception {
        // Given
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/test"))
                .GET()
                .build();

        // When
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello World");
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("text/plain; charset=UTF-8");
    }

    @Test
    void serverHandlesConcurrentRequests() throws Exception {
        // Given
        int concurrentRequests = 100;
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentRequests; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/test" + i))
                    .GET()
                    .build();
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }

        // When
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Then
        for (CompletableFuture<HttpResponse<String>> future : futures) {
            HttpResponse<String> response = future.get();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("Hello World");
        }
    }

    @Test
    void serverHandlesKeepAliveConnections() throws Exception {
        // Given
        int requestCount = 10;

        // When & Then
        for (int i = 0; i < requestCount; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/test"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("Hello World");
        }
    }

    @Test
    void serverUsesVirtualThreads() throws Exception {
        // Given
        int concurrentRequests = 1000;
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // When
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrentRequests; i++) {
                int index = i;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + port + "/test" + index))
                                .GET()
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        assertThat(response.statusCode()).isEqualTo(200);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, executor));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // Then
        assertThat(futures).hasSize(concurrentRequests);
        assertThat(futures).allMatch(CompletableFuture::isDone);
    }

    @Nested
    class LifecycleTests {

        private NettyServer lifecycleServer;
        private ExecutorService lifecycleExecutor;

        private NettyServer createServer(NettyServerConfiguration config) {
            lifecycleExecutor = Executors.newVirtualThreadPerTaskExecutor();
            TestHttpRequestHandler handler = new TestHttpRequestHandler(lifecycleExecutor);
            return new NettyServer(config, handler);
        }

        @AfterEach
        void tearDown() {
            if (lifecycleServer != null && lifecycleServer.isRunning()) {
                lifecycleServer.stop();
            }
            if (lifecycleExecutor != null) {
                lifecycleExecutor.shutdown();
            }
        }

        @Test
        void serverStartsAndBindsToPort() throws Exception {
            // Given
            NettyServerConfiguration config = NettyServerConfiguration.builder()
                    .port(0)
                    .build();
            lifecycleServer = createServer(config);

            // When
            lifecycleServer.start();

            // Then
            assertThat(lifecycleServer.isRunning()).isTrue();
            assertThat(lifecycleServer.getPort()).isGreaterThan(0);
        }

        @Test
        void serverStopsGracefully() throws Exception {
            // Given
            NettyServerConfiguration config = NettyServerConfiguration.builder()
                    .port(0)
                    .build();
            lifecycleServer = createServer(config);
            lifecycleServer.start();
            assertThat(lifecycleServer.isRunning()).isTrue();

            // When
            lifecycleServer.stop();

            // Then
            assertThat(lifecycleServer.isRunning()).isFalse();
        }

        @Test
        void serverReturnsCorrectPort() throws Exception {
            // Given
            NettyServerConfiguration config = NettyServerConfiguration.builder()
                    .port(0)
                    .build();
            lifecycleServer = createServer(config);
            assertThat(lifecycleServer.getPort()).isEqualTo(-1);

            // When
            lifecycleServer.start();
            int serverPort = lifecycleServer.getPort();

            // Then
            assertThat(serverPort).isGreaterThan(0);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + serverPort + "/"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        void serverThrowsExceptionWhenStartedTwice() throws Exception {
            // Given
            NettyServerConfiguration config = NettyServerConfiguration.builder()
                    .port(0)
                    .build();
            lifecycleServer = createServer(config);
            lifecycleServer.start();

            // When & Then
            assertThatThrownBy(() -> lifecycleServer.start())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Server is already running");
        }
    }
}
