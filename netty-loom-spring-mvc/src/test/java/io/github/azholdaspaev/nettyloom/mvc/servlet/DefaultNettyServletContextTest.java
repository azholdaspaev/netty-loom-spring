package io.github.azholdaspaev.nettyloom.mvc.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultNettyServletContextTest {

    private final DefaultNettyServletContext context = new DefaultNettyServletContext();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/test-resource.txt",
                "/static/style.css",
                "/foo/../test-resource.txt",
                "/./test-resource.txt",
                "/"
            })
    void shouldReturnNonNullUrlForValidPath(String path) throws MalformedURLException {
        // When
        URL url = context.getResource(path);

        // Then
        assertThat(url).isNotNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"no-slash.txt"})
    void shouldThrowMalformedUrlExceptionForInvalidPath(String path) {
        // When / Then
        assertThatThrownBy(() -> context.getResource(path)).isInstanceOf(MalformedURLException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/does-not-exist.txt", "/foo/../../etc/passwd"})
    void shouldReturnNullUrlForUnresolvablePath(String path) throws MalformedURLException {
        // When
        URL url = context.getResource(path);

        // Then
        assertThat(url).isNull();
    }

    @Test
    void shouldReturnInputStreamForExistingResource() throws Exception {
        // When / Then
        try (InputStream stream = context.getResourceAsStream("/test-resource.txt")) {
            assertThat(stream).isNotNull();
        }
    }

    @Test
    void shouldReturnReadableStream() throws Exception {
        // When / Then
        try (InputStream stream = context.getResourceAsStream("/test-resource.txt")) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes())).isEqualTo("hello");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/nope.txt", "no-slash"})
    void shouldReturnNullStreamForUnresolvablePath(String path) {
        // When
        InputStream stream = context.getResourceAsStream(path);

        // Then
        assertThat(stream).isNull();
    }

    @Test
    void shouldReturnClassLoader() {
        // When
        ClassLoader cl = context.getClassLoader();

        // Then
        assertThat(cl).isNotNull();
    }

    @Test
    void shouldUseProvidedClassLoader() {
        // Given
        ClassLoader custom = new ClassLoader() {};

        // When
        var customContext = new DefaultNettyServletContext(custom);

        // Then
        assertThat(customContext.getClassLoader()).isSameAs(custom);
    }
}
