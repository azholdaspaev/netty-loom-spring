package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPropertiesValidatorTest {

    @Test
    void shouldStayQuietWhenNothingIsConfigured() {
        assertEquals(List.of(), describeUnsupported());
    }

    @Test
    void shouldNameCompressionWhenEnabledExplicitly() {
        String warning = describeSingle("server.compression.enabled", "true");

        assertTrue(warning.contains("server.compression.enabled") && warning.contains("#22"),
            "the warning must name the property and its tracking issue, was: " + warning);
    }

    @Test
    void shouldStayQuietWhenCompressionIsDisabledExplicitly() {
        assertEquals(List.of(), describeUnsupported("server.compression.enabled", "false"));
    }

    @Test
    void shouldNameHttp2WhenEnabledExplicitly() {
        String warning = describeSingle("server.http2.enabled", "true");

        assertTrue(warning.contains("server.http2.enabled") && warning.contains("#23"),
            "the warning must name the property and its tracking issue, was: " + warning);
    }

    @Test
    void shouldStayQuietWhenHttp2IsDisabledExplicitly() {
        assertEquals(List.of(), describeUnsupported("server.http2.enabled", "false"));
    }

    @Test
    void shouldNameHttp2WhenEnabledThroughEnvironmentVariable() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("SERVER_HTTP2_ENABLED", "true")));

        List<String> warnings = new ServerPropertiesValidator(environment).describeUnsupported();

        assertTrue(warnings.size() == 1 && warnings.getFirst().contains("server.http2.enabled"),
            "SERVER_HTTP2_ENABLED must be read as server.http2.enabled, was: " + warnings);
    }

    @Test
    void shouldNameServerHeaderWhenSetExplicitly() {
        String warning = describeSingle("server.server-header", "netty-loom");

        assertTrue(warning.contains("server.server-header") && warning.contains("#167"),
            "the warning must name the property and its tracking issue, was: " + warning);
    }

    @Test
    void shouldStayQuietWhenServerHeaderIsBlank() {
        assertEquals(List.of(), describeUnsupported("server.server-header", ""));
    }

    @Test
    void shouldNameDefaultServletWhenRegisteredExplicitly() {
        String warning = describeSingle("server.servlet.register-default-servlet", "true");

        assertTrue(warning.contains("server.servlet.register-default-servlet"),
            "the warning must name the property, was: " + warning);
    }

    @Test
    void shouldStayQuietWhenDefaultServletIsNotRegisteredExplicitly() {
        assertEquals(List.of(), describeUnsupported("server.servlet.register-default-servlet", "false"));
    }

    @Test
    void shouldNameEveryJspKeyWhenConfigured() {
        String warning = describeSingle(
            "server.servlet.jsp.class-name", "org.example.Jsp",
            "server.servlet.jsp.init-parameters.development", "false");

        assertTrue(warning.contains("server.servlet.jsp.class-name")
                && warning.contains("server.servlet.jsp.init-parameters.development"),
            "the warning must name each configured key, was: " + warning);
    }

    @Test
    void shouldNameJspWhenRegisteredExplicitly() {
        String warning = describeSingle("server.servlet.jsp.registered", "true");

        assertTrue(warning.contains("server.servlet.jsp.registered"),
            "Boot's default registers JSP but this server never does, so an explicit true warns, was: " + warning);
    }

    @Test
    void shouldStayQuietWhenJspIsUnregisteredExplicitly() {
        assertEquals(List.of(), describeUnsupported("server.servlet.jsp.registered", "false"));
    }

    @Test
    void shouldStayQuietWhenOnlyDevToolsDefaultsAreSet() {
        MockEnvironment environment = withDevToolsDefaults(new MockEnvironment());

        assertEquals(List.of(), new ServerPropertiesValidator(environment).describeUnsupported(),
            "DevTools defaults server.servlet.jsp.init-parameters.development=true; that is not user configuration");
    }

    @Test
    void shouldNameJspKeyWhenUserSetsKeyDevToolsAlsoDefaults() {
        MockEnvironment environment = withDevToolsDefaults(new MockEnvironment());
        environment.setProperty("server.servlet.jsp.init-parameters.development", "true");

        List<String> warnings = new ServerPropertiesValidator(environment).describeUnsupported();

        assertTrue(warnings.size() == 1 && warnings.getFirst().contains("server.servlet.jsp.init-parameters.development"),
            "only the DevTools source is skipped, not the key, was: " + warnings);
    }

    @Test
    void shouldNameLocaleCharsetMappingWhenConfigured() {
        String warning = describeSingle("server.servlet.encoding.mapping.de", "UTF-8");

        assertTrue(warning.contains("server.servlet.encoding.mapping.de"),
            "the warning must name the configured key, was: " + warning);
    }

    @Test
    void shouldNameSessionStoreDirWhenConfigured() {
        String warning = describeSingle("server.servlet.session.store-dir", "/var/sessions");

        assertTrue(warning.contains("server.servlet.session.store-dir"),
            "the warning must name the property, was: " + warning);
    }

    @Test
    void shouldPointHeaderSizeAtNettyPropertyWhenConfigured() {
        String warning = describeSingle("server.max-http-request-header-size", "16KB");

        assertTrue(warning.contains("server.max-http-request-header-size")
                && warning.contains("server.netty.max-header-size"),
            "the warning must name the property and its server.netty.* equivalent, was: " + warning);
    }

    @Test
    void shouldNameServletPathWhenSetOffRoot() {
        String warning = describeSingle("spring.mvc.servlet.path", "/api");

        assertTrue(warning.contains("spring.mvc.servlet.path"), "the warning must name the property, was: " + warning);
    }

    @Test
    void shouldStayQuietWhenServletPathIsRootExplicitly() {
        assertEquals(List.of(), describeUnsupported("spring.mvc.servlet.path", "/"));
    }

    @Test
    void shouldPointNettyPortAtServerPortWhenConfigured() {
        String warning = describeSingle("server.netty.port", "9090");

        assertTrue(warning.contains("server.netty.port") && warning.contains("server.port"),
            "the warning must name the removed property and its replacement, was: " + warning);
    }

    @Test
    void shouldMapTomcatKeysToNettyEquivalentsInOneWarning() {
        String warning = describeSingle(
            "server.tomcat.accept-count", "200",
            "server.tomcat.threads.max", "50");

        assertTrue(warning.contains("server.tomcat.accept-count") && warning.contains("server.netty.accept-count"),
            "accept-count must point at server.netty.accept-count, was: " + warning);
        assertTrue(warning.contains("server.tomcat.threads.max") && warning.contains("virtual threads"),
            "threads.* must say requests run on virtual threads, was: " + warning);
    }

    @Test
    void shouldPointTomcatMaxConnectionsAtItsIssue() {
        String warning = describeSingle("server.tomcat.max-connections", "1000");

        assertTrue(warning.contains("server.tomcat.max-connections") && warning.contains("#45"),
            "max-connections must name its tracking issue, was: " + warning);
    }

    @Test
    void shouldNameJettyKeyWhenConfigured() {
        String warning = describeSingle("server.jetty.threads.max", "50");

        assertTrue(warning.contains("server.jetty.threads.max") && warning.contains("Jetty"),
            "the warning must name the key and the container, was: " + warning);
    }

    @Test
    void shouldSayBootHasNoUndertowWhenUndertowKeyIsConfigured() {
        String warning = describeSingle("server.undertow.threads.io", "4");

        assertTrue(warning.contains("server.undertow.threads.io") && warning.contains("no Undertow support"),
            "the warning must name the key and that Boot 4 dropped Undertow, was: " + warning);
    }

    private static MockEnvironment withDevToolsDefaults(MockEnvironment environment) {
        environment.getPropertySources().addLast(new MapPropertySource("devtools",
            Map.of("server.servlet.jsp.init-parameters.development", "true")));
        return environment;
    }

    private static String describeSingle(String... properties) {
        List<String> warnings = describeUnsupported(properties);
        assertEquals(1, warnings.size(), "expected exactly one warning, was: " + warnings);
        return warnings.getFirst();
    }

    private static List<String> describeUnsupported(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < properties.length; i += 2) {
            environment.setProperty(properties[i], properties[i + 1]);
        }
        return new ServerPropertiesValidator(environment).describeUnsupported();
    }
}
