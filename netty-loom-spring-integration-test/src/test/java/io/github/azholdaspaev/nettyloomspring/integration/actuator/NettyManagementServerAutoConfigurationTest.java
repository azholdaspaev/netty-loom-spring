package io.github.azholdaspaev.nettyloomspring.integration.actuator;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.NettyLoomAutoConfiguration;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.management.NettyManagementServerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.web.servlet.DispatcherServlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME;

class NettyManagementServerAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NettyLoomAutoConfiguration.class,
            NettyManagementServerAutoConfiguration.class))
        .withBean(DEFAULT_DISPATCHER_SERVLET_BEAN_NAME, DispatcherServlet.class, DispatcherServlet::new);

    @Test
    void shouldDeclareManagementContextFactoryWhenPortDiffers() {
        runner.withPropertyValues("management.server.port=0")
            .run(context -> assertThat(context).hasSingleBean(ManagementContextFactory.class));
    }

    @Test
    void shouldSkipManagementContextFactoryWhenPortIsSame() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ManagementContextFactory.class));
    }

    @Test
    void shouldSkipManagementContextFactoryForUserServerFactory() {
        runner.withPropertyValues("management.server.port=0")
            .withBean("userServerFactory", ServletWebServerFactory.class, () -> initializers -> null)
            .run(context -> assertThat(context).doesNotHaveBean(ManagementContextFactory.class));
    }
}
