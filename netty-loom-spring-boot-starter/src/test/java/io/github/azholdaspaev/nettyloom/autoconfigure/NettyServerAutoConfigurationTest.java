package io.github.azholdaspaev.nettyloom.autoconfigure;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NettyServerAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NettyServerAutoConfiguration.class));

    @Nested
    class BeanCreation {

        @Test
        void shouldCreateFactoryBeanWhenConditionsMet() {
            contextRunner
                    .run(context -> {
                        assertThat(context).hasSingleBean(NettyServletWebServerFactory.class);
                        assertThat(context).hasSingleBean(NettyServerProperties.class);
                    });
        }

        @Test
        void shouldCreatePropertiesBean() {
            contextRunner
                    .run(context -> {
                        assertThat(context).hasSingleBean(NettyServerProperties.class);
                        NettyServerProperties props = context.getBean(NettyServerProperties.class);
                        assertThat(props.getBossThreads()).isEqualTo(1);
                    });
        }
    }

    @Nested
    class ConditionalActivation {

        @Test
        void shouldBackOffWhenAnotherServletWebServerFactoryPresent() {
            contextRunner
                    .withUserConfiguration(CustomFactoryConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(ServletWebServerFactory.class);
                        assertThat(context).doesNotHaveBean(NettyServletWebServerFactory.class);
                    });
        }
    }

    @Nested
    class PropertyBinding {

        @Test
        void shouldBindCustomProperties() {
            contextRunner
                    .withPropertyValues(
                            "server.netty.boss-threads=2",
                            "server.netty.worker-threads=8",
                            "server.netty.max-content-length=20971520"
                    )
                    .run(context -> {
                        NettyServerProperties props = context.getBean(NettyServerProperties.class);
                        assertThat(props.getBossThreads()).isEqualTo(2);
                        assertThat(props.getWorkerThreads()).isEqualTo(8);
                        assertThat(props.getMaxContentLength()).isEqualTo(20971520);
                    });
        }

        @Test
        void shouldApplyPropertiesToFactory() {
            contextRunner
                    .withPropertyValues(
                            "server.netty.boss-threads=4",
                            "server.netty.worker-threads=16"
                    )
                    .run(context -> {
                        NettyServletWebServerFactory factory = context.getBean(NettyServletWebServerFactory.class);
                        assertThat(factory.getNettyProperties().getBossThreads()).isEqualTo(4);
                        assertThat(factory.getNettyProperties().getWorkerThreads()).isEqualTo(16);
                    });
        }
    }

    @Nested
    class Customization {

        @Test
        void shouldApplyCustomizers() {
            contextRunner
                    .withUserConfiguration(CustomizerConfiguration.class)
                    .run(context -> {
                        NettyServletWebServerFactory factory = context.getBean(NettyServletWebServerFactory.class);
                        // The customizer should have set the port to 9999
                        assertThat(factory.getPort()).isEqualTo(9999);
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomFactoryConfiguration {
        @Bean
        ServletWebServerFactory customServletWebServerFactory() {
            return mock(ServletWebServerFactory.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomizerConfiguration {
        @Bean
        org.springframework.boot.web.server.WebServerFactoryCustomizer<NettyServletWebServerFactory> nettyCustomizer() {
            return factory -> factory.setPort(9999);
        }
    }
}
