package io.github.azholdaspaev.nettyloomspring.autoconfigure.management;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.NettyLoomAutoConfiguration;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.server.NettyServletWebServerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextFactory;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;

/**
 * The child context runs {@link NettyLoomAutoConfiguration} itself, rather than reusing the parent's
 * beans, because every bean there is guarded with {@code SearchStrategy.CURRENT}: the child gets its
 * own servlet context, connection registry and pipeline, so the management port serves only the
 * management context. Boot's {@code TomcatServletManagementContextAutoConfiguration} is the model.
 */
@AutoConfiguration(after = NettyLoomAutoConfiguration.class)
@ConditionalOnClass(ManagementContextFactory.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
@ConditionalOnBean(value = NettyServletWebServerFactory.class, search = SearchStrategy.CURRENT)
public class NettyManagementServerAutoConfiguration {

    @Bean
    static ManagementContextFactory nettyManagementContextFactory() {
        return new ManagementContextFactory(WebApplicationType.SERVLET, ServletWebServerFactory.class,
            NettyLoomAutoConfiguration.class);
    }
}
