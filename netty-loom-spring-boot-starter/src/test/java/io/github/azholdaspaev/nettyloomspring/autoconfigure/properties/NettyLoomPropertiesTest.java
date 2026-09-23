package io.github.azholdaspaev.nettyloomspring.autoconfigure.properties;

import io.github.azholdaspaev.nettyloomspring.core.server.NettyTransportPreference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyLoomPropertiesTest {

    private static final String CONFIGURATION_DOCS_URL =
        "https://github.com/azholdaspaev/netty-loom-spring/blob/main/docs/configuration.md#servernetty";

    @Test
    void shouldPointEveryMetadataDescriptionAtConfigurationDocs() throws Exception {
        Map<String, String> descriptions = new HashMap<>();
        try (InputStream metadata = NettyLoomProperties.class.getClassLoader()
            .getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
            assertNotNull(metadata, "spring-configuration-metadata.json must be generated");
            for (JsonNode property : new ObjectMapper().readTree(metadata).path("properties")) {
                descriptions.put(property.path("name").asString(), property.path("description").asString(""));
            }
        }

        for (RecordComponent component : NettyLoomProperties.class.getRecordComponents()) {
            String name = "server.netty." + DataObjectPropertyName.toDashedForm(component.getName());
            String description = descriptions.get(name);
            assertNotNull(description, name + " must have a metadata entry");
            assertTrue(description.endsWith(" See " + CONFIGURATION_DOCS_URL),
                name + " must end by pointing at " + CONFIGURATION_DOCS_URL
                    + ", the owner of its semantics, but reads: " + description);
            String summary = description.substring(0, description.lastIndexOf(" See "));
            assertFalse(summary.contains(". "),
                name + " must summarise in one sentence, leaving the rest and the default to their owners,"
                    + " but reads: " + description);
        }
    }

    @Test
    void shouldApplyDefaultReadTimeout() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(Duration.ofSeconds(30), properties.readTimeout());
    }

    @Test
    void shouldOverrideReadTimeoutFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.read-timeout", "5s"));

        assertEquals(Duration.ofSeconds(5), properties.readTimeout());
    }

    @Test
    void shouldApplyDefaultWriteStallTimeout() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(Duration.ofSeconds(60), properties.writeStallTimeout());
    }

    @Test
    void shouldOverrideWriteStallTimeoutFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.write-stall-timeout", "5s"));

        assertEquals(Duration.ofSeconds(5), properties.writeStallTimeout());
    }

    @Test
    void shouldApplyDefaultAcceptCount() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(128, properties.acceptCount());
    }

    @Test
    void shouldOverrideAcceptCountFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.accept-count", "1024"));

        assertEquals(1024, properties.acceptCount());
    }

    @Test
    void shouldApplyDefaultMaxHttpBodySize() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(DataSize.ofMegabytes(1), properties.maxHttpBodySize());
    }

    @Test
    void shouldOverrideMaxHttpBodySizeFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.max-http-body-size", "8MB"));

        assertEquals(DataSize.ofMegabytes(8), properties.maxHttpBodySize());
    }

    @Test
    void shouldApplyDefaultMaxHeaderSize() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(DataSize.ofBytes(10_000), properties.maxHeaderSize());
    }

    @Test
    void shouldOverrideMaxHeaderSizeFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.max-header-size", "16KB"));

        assertEquals(DataSize.ofKilobytes(16), properties.maxHeaderSize());
    }

    @Test
    void shouldApplyDefaultMaxInitialLineLength() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(DataSize.ofBytes(10_000), properties.maxInitialLineLength());
    }

    @Test
    void shouldOverrideMaxInitialLineLengthFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.max-initial-line-length", "4KB"));

        assertEquals(DataSize.ofKilobytes(4), properties.maxInitialLineLength());
    }

    @Test
    void shouldApplyDefaultMaxChunkSize() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(DataSize.ofBytes(10_000), properties.maxChunkSize());
    }

    @Test
    void shouldOverrideMaxChunkSizeFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.max-chunk-size", "64KB"));

        assertEquals(DataSize.ofKilobytes(64), properties.maxChunkSize());
    }

    @Test
    void shouldDefaultTransportToAuto() {
        NettyLoomProperties properties = bind(Map.of());

        assertEquals(NettyTransportPreference.AUTO, properties.transport());
    }

    @Test
    void shouldOverrideTransportFromConfiguration() {
        NettyLoomProperties properties = bind(Map.of("server.netty.transport", "nio"));

        assertEquals(NettyTransportPreference.NIO, properties.transport());
    }

    @Test
    void shouldFallBackToAutoWhenTransportIsBlank() {
        NettyLoomProperties properties = bind(Map.of("server.netty.transport", ""));

        assertEquals(NettyTransportPreference.AUTO, properties.transport());
    }

    private static NettyLoomProperties bind(Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source))
            .bindOrCreate("server.netty", NettyLoomProperties.class);
    }
}
