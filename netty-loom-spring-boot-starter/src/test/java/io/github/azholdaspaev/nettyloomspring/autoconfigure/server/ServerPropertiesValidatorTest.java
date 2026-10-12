package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

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
