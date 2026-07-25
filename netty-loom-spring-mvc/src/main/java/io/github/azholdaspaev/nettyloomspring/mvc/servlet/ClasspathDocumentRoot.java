package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ResourceUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.stream.Stream;

/**
 * The document root a {@link jakarta.servlet.ServletContext} serves its resources from, backed by the
 * classpath's {@code META-INF/resources/} tree.
 *
 * <p>A servlet container resolves context resources against the deployed web application. This server
 * has no exploded WAR, so — exactly as Tomcat does for a jar-packaged Spring Boot app — that role falls
 * to {@code META-INF/resources/}. Boot's other static locations ({@code classpath:/static/},
 * {@code /public/}, {@code /resources/}) are deliberately <em>not</em> part of it: Spring's
 * {@code ResourceHttpRequestHandler} serves those off the classpath directly, never through the
 * ServletContext, so on Tomcat {@code getResource("/static/x")} is {@code null} and it is {@code null}
 * here too.
 *
 * <p>Paths are context-relative and must start with {@code "/"}.
 */
final class ClasspathDocumentRoot {

    private static final Logger log = LoggerFactory.getLogger(ClasspathDocumentRoot.class);

    private static final String LOCATION_PREFIX = "META-INF/resources";

    private final ClassLoader classLoader;

    ClasspathDocumentRoot(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    ClassLoader classLoader() {
        return classLoader;
    }

    /**
     * @throws MalformedURLException if {@code path} is not a valid context resource path
     */
    URL resource(String path) throws MalformedURLException {
        if (!isValidPath(path)) {
            throw new MalformedURLException("Resource path must start with '/': " + path);
        }
        return classLoader.getResource(locationOf(path));
    }

    /**
     * Unlike {@link #resource}, the spec has the stream lookup signal a bad path with {@code null}
     * rather than an exception.
     */
    InputStream stream(String path) {
        return isValidPath(path) ? classLoader.getResourceAsStream(locationOf(path)) : null;
    }

    /**
     * Immediate children of {@code path}, as context-relative paths with subdirectories slash-terminated.
     * {@code null} when the directory holds nothing or does not exist — the spec does not distinguish them.
     */
    Set<String> paths(String path) {
        if (!isValidPath(path)) {
            return null;
        }
        String directory = path.endsWith("/") ? path : path + "/";

        Set<String> children = new LinkedHashSet<>();
        try {
            // The document root is a merged view: several classpath entries may each contribute the
            // same directory, so all of them are listed.
            //
            // A directory inside a jar is found only when the jar carries an entry for it. Jars are not
            // obliged to, though every mainstream build tool writes them; the alternative — scanning
            // every classpath jar for names under the prefix — costs a full index walk per call to spare
            // a jar no standard toolchain produces. resource()/stream() address entries directly and are
            // unaffected either way.
            Enumeration<URL> directories = classLoader.getResources(locationOf(directory));
            while (directories.hasMoreElements()) {
                collectChildren(directories.nextElement(), directory, children);
            }
        } catch (IOException | URISyntaxException ex) {
            log.warn("Failed to list context resources under '{}'", path, ex);
            return null;
        }
        return children.isEmpty() ? null : Collections.unmodifiableSet(children);
    }

    /**
     * Adds the immediate children of one classpath directory.
     *
     * <p>Names come from the filesystem or the jar index rather than from parsing {@code directoryUrl}:
     * a URL renders {@code "my file.txt"} percent-encoded, and the paths returned here have to be literal
     * enough to hand straight back to {@link #resource}.
     */
    private static void collectChildren(URL directoryUrl, String directory, Set<String> children)
            throws IOException, URISyntaxException {
        switch (directoryUrl.getProtocol()) {
            case ResourceUtils.URL_PROTOCOL_FILE -> {
                try (Stream<Path> entries = Files.list(Path.of(directoryUrl.toURI()))) {
                    entries.forEach(entry -> addChild(directory,
                        entry.getFileName() + (Files.isDirectory(entry) ? "/" : ""), children));
                }
            }
            case ResourceUtils.URL_PROTOCOL_JAR -> {
                String location = locationOf(directory);
                JarURLConnection connection = (JarURLConnection) directoryUrl.openConnection();
                // The JarFile is the JVM's cached, shared instance — the one the class loader itself is
                // using — so it must not be closed here. That contract holds only while caching is on,
                // which some containers disable process-wide; ask for it explicitly.
                connection.setUseCaches(true);
                // A jar entry has no children of its own, so the index is scanned for names under this
                // directory.
                connection.getJarFile().stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.startsWith(location) && !name.equals(location))
                    .forEach(name -> addChild(directory, name.substring(location.length()), children));
            }
            default -> log.warn("Cannot list context resources under '{}': unsupported protocol '{}'",
                directory, directoryUrl.getProtocol());
        }
    }

    /** A jar entry name may be nested, so only its first segment is an immediate child. */
    private static void addChild(String directory, String relative, Set<String> children) {
        int separator = relative.indexOf('/');
        children.add(directory + (separator < 0 ? relative : relative.substring(0, separator + 1)));
    }

    private static String locationOf(String path) {
        return LOCATION_PREFIX + path;
    }

    /** The spec requires a leading {@code "/"}: resource paths are relative to the document root. */
    private static boolean isValidPath(String path) {
        return path != null && path.startsWith("/");
    }
}
