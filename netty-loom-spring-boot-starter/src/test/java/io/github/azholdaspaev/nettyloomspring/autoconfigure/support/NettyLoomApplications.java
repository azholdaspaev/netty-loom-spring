package io.github.azholdaspaev.nettyloomspring.autoconfigure.support;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.app.SmokeNettyLoomApplication;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the smoke application on a random port and reaches the container's {@link NettyServletContext}
 * from any booted application. The lookup is by name, not type: a web application context republishes
 * the live {@code ServletContext} -- this same instance -- as a bean named {@code servletContext}
 * ({@code WebApplicationContextUtils.registerEnvironmentBeans}), so a by-type lookup is ambiguous.
 */
public final class NettyLoomApplications {

    private NettyLoomApplications() {
    }

    public static ConfigurableApplicationContext run(String... properties) {
        String[] all = new String[properties.length + 1];
        all[0] = "server.port=0";
        System.arraycopy(properties, 0, all, 1, properties.length);
        return new SpringApplicationBuilder(SmokeNettyLoomApplication.class).properties(all).run();
    }

    public static NettyServletContext servletContext(ConfigurableApplicationContext context) {
        return context.getBean("nettyServletContext", NettyServletContext.class);
    }
}
