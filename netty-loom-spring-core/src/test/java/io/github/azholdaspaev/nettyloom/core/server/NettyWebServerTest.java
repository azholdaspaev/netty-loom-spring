package io.github.azholdaspaev.nettyloom.core.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.server.WebServerException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettyWebServerTest {

    private NettyServerConfiguration config;

    @BeforeEach
    void setUp() {
        config = NettyServerConfiguration.builder()
                .port(0)  // Ephemeral port
                .host("localhost")
                .build();
    }

    @Nested
    class Lifecycle {

        @Test
        void shouldStartAndStopServer() throws Exception {
            // Given
            NettyServer nettyServer = new NettyServer(config);
            NettyWebServer webServer = new NettyWebServer(nettyServer);

            // When
            webServer.start();

            // Then
            assertThat(webServer.getPort()).isGreaterThan(0);
            assertThat(webServer.isRunning()).isTrue();

            // When
            webServer.stop();

            // Then
            assertThat(webServer.isRunning()).isFalse();
        }

        @Test
        void shouldBeIdempotentOnMultipleStarts() throws Exception {
            // Given
            NettyServer nettyServer = new NettyServer(config);
            NettyWebServer webServer = new NettyWebServer(nettyServer);

            // When
            webServer.start();
            int port1 = webServer.getPort();
            webServer.start();  // Should be idempotent
            int port2 = webServer.getPort();

            // Then
            assertThat(port1).isEqualTo(port2);
            assertThat(webServer.isRunning()).isTrue();

            // Cleanup
            webServer.stop();
        }

        @Test
        void shouldBeIdempotentOnMultipleStops() throws Exception {
            // Given
            NettyServer nettyServer = new NettyServer(config);
            NettyWebServer webServer = new NettyWebServer(nettyServer);
            webServer.start();

            // When
            webServer.stop();
            webServer.stop();  // Should be idempotent

            // Then - no exception thrown
            assertThat(webServer.isRunning()).isFalse();
        }

        @Test
        void shouldReturnNegativePortWhenNotStarted() {
            // Given
            NettyServer nettyServer = new NettyServer(config);
            NettyWebServer webServer = new NettyWebServer(nettyServer);

            // When
            int port = webServer.getPort();

            // Then
            assertThat(port).isEqualTo(-1);
        }
    }

    @Nested
    class GracefulShutdown {

        @Test
        void shouldInvokeCallbackOnGracefulShutdown() throws Exception {
            // Given
            NettyServer nettyServer = new NettyServer(config);
            NettyWebServer webServer = new NettyWebServer(nettyServer, Duration.ofSeconds(5));
            webServer.start();

            AtomicReference<GracefulShutdownResult> resultRef = new AtomicReference<>();
            GracefulShutdownCallback callback = resultRef::set;

            // When
            webServer.shutDownGracefully(callback);

            // Then
            assertThat(resultRef.get()).isEqualTo(GracefulShutdownResult.IDLE);
            assertThat(webServer.isRunning()).isFalse();
        }
    }

    @Nested
    class DefaultTimeout {

        @Test
        void shouldUseDefaultTimeoutIfNotProvided() {
            // Given
            NettyServer nettyServer = new NettyServer(config);

            // When
            NettyWebServer webServer = new NettyWebServer(nettyServer);

            // Then - no exception thrown, default timeout used
            assertThat(webServer).isNotNull();
        }
    }

    @Nested
    class NettyServerAccess {

        @Test
        void shouldProvideAccessToUnderlyingNettyServer() {
            // Given
            NettyServer nettyServer = new NettyServer(config);
            NettyWebServer webServer = new NettyWebServer(nettyServer);

            // When
            NettyServer returned = webServer.getNettyServer();

            // Then
            assertThat(returned).isSameAs(nettyServer);
        }
    }
}
