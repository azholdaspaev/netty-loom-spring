package io.github.azholdaspaev.nettyloom.autoconfigure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NettyServerPropertiesTest {

    private NettyServerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NettyServerProperties();
    }

    @Nested
    class DefaultValues {

        @Test
        void shouldHaveDefaultBossThreads() {
            assertThat(properties.getBossThreads()).isEqualTo(1);
        }

        @Test
        void shouldHaveDefaultWorkerThreads() {
            assertThat(properties.getWorkerThreads()).isEqualTo(0);
        }

        @Test
        void shouldHaveDefaultMaxContentLength() {
            // 10 MB
            assertThat(properties.getMaxContentLength()).isEqualTo(10 * 1024 * 1024);
        }

        @Test
        void shouldHaveDefaultConnectionTimeout() {
            assertThat(properties.getConnectionTimeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void shouldHaveDefaultIdleTimeout() {
            assertThat(properties.getIdleTimeout()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        void shouldHaveDefaultShutdownTimeout() {
            assertThat(properties.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void shouldHaveDefaultServerHeader() {
            assertThat(properties.getServerHeader()).isEqualTo("Netty-Loom");
        }
    }

    @Nested
    class SettersAndGetters {

        @Test
        void shouldSetAndGetBossThreads() {
            properties.setBossThreads(2);
            assertThat(properties.getBossThreads()).isEqualTo(2);
        }

        @Test
        void shouldSetAndGetWorkerThreads() {
            properties.setWorkerThreads(4);
            assertThat(properties.getWorkerThreads()).isEqualTo(4);
        }

        @Test
        void shouldSetAndGetMaxContentLength() {
            properties.setMaxContentLength(5 * 1024 * 1024);
            assertThat(properties.getMaxContentLength()).isEqualTo(5 * 1024 * 1024);
        }

        @Test
        void shouldSetAndGetConnectionTimeout() {
            Duration timeout = Duration.ofSeconds(60);
            properties.setConnectionTimeout(timeout);
            assertThat(properties.getConnectionTimeout()).isEqualTo(timeout);
        }

        @Test
        void shouldSetAndGetIdleTimeout() {
            Duration timeout = Duration.ofMinutes(5);
            properties.setIdleTimeout(timeout);
            assertThat(properties.getIdleTimeout()).isEqualTo(timeout);
        }

        @Test
        void shouldSetAndGetShutdownTimeout() {
            Duration timeout = Duration.ofSeconds(45);
            properties.setShutdownTimeout(timeout);
            assertThat(properties.getShutdownTimeout()).isEqualTo(timeout);
        }

        @Test
        void shouldSetAndGetServerHeader() {
            properties.setServerHeader("Custom-Server");
            assertThat(properties.getServerHeader()).isEqualTo("Custom-Server");
        }
    }
}
