package io.github.azholdaspaev.nettyloom.autoconfigure;

import io.github.azholdaspaev.nettyloom.autoconfigure.server.NettyWebServer;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NettyServletWebServerFactoryTest {

    private NettyServletWebServerFactory factory;
    private WebServer webServer;

    @BeforeEach
    void setUp() {
        factory = new NettyServletWebServerFactory(new NettyServerProperties());
        factory.setPort(0);  // Ephemeral port
    }

    @AfterEach
    void tearDown() {
        if (webServer != null) {
            webServer.stop();
        }
    }

    @Nested
    class WebServerCreation {

        @Test
        void shouldCreateWebServer() {
            // When
            webServer = factory.getWebServer();

            // Then
            assertThat(webServer).isNotNull();
            assertThat(webServer).isInstanceOf(NettyWebServer.class);
        }

        @Test
        void shouldStartWebServer() {
            // Given
            webServer = factory.getWebServer();

            // When
            webServer.start();

            // Then
            assertThat(webServer.getPort()).isGreaterThan(0);
        }

        @Test
        void shouldApplyInitializers() {
            // Given
            AtomicBoolean initializerCalled = new AtomicBoolean(false);
            AtomicReference<ServletContext> contextRef = new AtomicReference<>();

            ServletContextInitializer initializer = (context) -> {
                initializerCalled.set(true);
                contextRef.set(context);
            };

            // When
            webServer = factory.getWebServer(initializer);

            // Then
            assertThat(initializerCalled.get()).isTrue();
            assertThat(contextRef.get()).isNotNull();
        }

        @Test
        void shouldApplyMultipleInitializers() {
            // Given
            AtomicBoolean first = new AtomicBoolean(false);
            AtomicBoolean second = new AtomicBoolean(false);

            ServletContextInitializer init1 = (context) -> first.set(true);
            ServletContextInitializer init2 = (context) -> second.set(true);

            // When
            webServer = factory.getWebServer(init1, init2);

            // Then
            assertThat(first.get()).isTrue();
            assertThat(second.get()).isTrue();
        }
    }

    @Nested
    class Configuration {

        @Test
        void shouldUseDefaultProperties() {
            // Given
            factory = new NettyServletWebServerFactory();
            factory.setPort(0);

            // When
            webServer = factory.getWebServer();

            // Then
            assertThat(factory.getNettyProperties()).isNotNull();
            assertThat(factory.getNettyProperties().getBossThreads()).isEqualTo(1);
        }

        @Test
        void shouldUseCustomProperties() {
            // Given
            NettyServerProperties props = new NettyServerProperties();
            props.setBossThreads(2);
            props.setWorkerThreads(8);

            factory = new NettyServletWebServerFactory(props);
            factory.setPort(0);

            // When
            webServer = factory.getWebServer();

            // Then
            assertThat(factory.getNettyProperties().getBossThreads()).isEqualTo(2);
            assertThat(factory.getNettyProperties().getWorkerThreads()).isEqualTo(8);
        }

        @Test
        void shouldApplyContextPath() {
            // Given
            factory.setContextPath("/api");
            AtomicReference<String> contextPathRef = new AtomicReference<>();

            ServletContextInitializer initializer = (context) -> {
                contextPathRef.set(context.getContextPath());
            };

            // When
            webServer = factory.getWebServer(initializer);

            // Then
            assertThat(contextPathRef.get()).isEqualTo("/api");
        }

        @Test
        void shouldHandleEmptyContextPath() {
            // Given
            factory.setContextPath("");
            AtomicReference<String> contextPathRef = new AtomicReference<>();

            ServletContextInitializer initializer = (context) -> {
                contextPathRef.set(context.getContextPath());
            };

            // When
            webServer = factory.getWebServer(initializer);

            // Then
            assertThat(contextPathRef.get()).isEmpty();
        }
    }

    @Nested
    class EphemeralPort {

        @Test
        void shouldBindToEphemeralPort() {
            // Given
            factory.setPort(0);

            // When
            webServer = factory.getWebServer();
            webServer.start();

            // Then
            assertThat(webServer.getPort()).isGreaterThan(0);
            assertThat(webServer.getPort()).isLessThan(65536);
        }
    }
}
