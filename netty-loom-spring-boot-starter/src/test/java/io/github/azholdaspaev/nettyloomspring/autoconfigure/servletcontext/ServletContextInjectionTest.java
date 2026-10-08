package io.github.azholdaspaev.nettyloomspring.autoconfigure.servletcontext;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.smoke.test.BaseIntegrationTest;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * By-type {@code ServletContext} injection must resolve to the running container's context (#330), as
 * it does under Tomcat, which exposes exactly one bean of that type.
 */
class ServletContextInjectionTest extends BaseIntegrationTest {

    @Autowired
    private ServletContext injected;

    @Autowired
    private WebApplicationContext context;

    @Test
    void shouldInjectRunningServletContextByType() {
        assertThat(injected)
            .as("a field not named after either bean must still get the container's context")
            .isSameAs(context.getServletContext())
            .isInstanceOf(NettyServletContext.class);
    }

    @Test
    void shouldResolveRunningServletContextFromGetBeanByType() {
        assertThat(context.getBean(ServletContext.class)).isSameAs(context.getServletContext());
    }
}
