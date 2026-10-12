package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import org.junit.jupiter.api.Test;
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
