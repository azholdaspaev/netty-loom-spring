package io.github.azholdaspaev.nettyloom.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.azholdaspaev.nettyloom.core.http.DefaultNettyHttpResponse;
import io.github.azholdaspaev.nettyloom.core.pipeline.HttpServerNettyPipelineConfigurer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

public class NettyServerTest {

    @Test
    void shouldReturnResponseWhenGetInfo() throws Exception {
        // Given
        NettyServerConfig config = NettyServerConfig.builder()
                .port(8080)
                .bossThreads(1)
                .workerThreads(1)
                .maxInitialLineLength(4096)
                .maxHeaderSize(8192)
                .maxChunkSize(8192)
                .maxContentLength(1048576)
                .idleTimeout(Duration.ofSeconds(30))
                .build();

        NettyServerInitializer nettyServerInitializer = new NettyServerInitializer(
                _ -> DefaultNettyHttpResponse.builder().build(),
                (ex, request) -> DefaultNettyHttpResponse.builder().build(),
                new HttpServerNettyPipelineConfigurer(config));

        NettyServer nettyServer = new NettyServer(config, nettyServerInitializer);

        try {
            nettyServer.start();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/info"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            // When
            HttpResponse<String> httpResponse =
                    HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());

            // Then
            assertThat(httpResponse.statusCode()).isEqualTo(200);
        } finally {
            nettyServer.stop();
        }
    }
}
