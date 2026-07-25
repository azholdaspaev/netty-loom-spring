package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRegistration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultNettyServletContextTest {

    private DefaultNettyServletContext context;

    @BeforeEach
    void setUp() {
        context = new DefaultNettyServletContext(DefaultNettyServletContextTest.class.getClassLoader());
    }

    // --- Attribute methods ---

    @Test
    void shouldReturnNullForUnknownAttribute() {
        assertNull(context.getAttribute("unknown"));
    }

    @Test
    void shouldSetAndGetAttribute() {
        context.setAttribute("key", "value");

        assertEquals("value", context.getAttribute("key"));
    }

    @Test
    void shouldRemoveAttributeWhenSetToNull() {
        context.setAttribute("key", "value");

        context.setAttribute("key", null);

        assertNull(context.getAttribute("key"));
        assertFalse(collectNames(context.getAttributeNames()).contains("key"));
    }

    @Test
    void shouldRemoveAttribute() {
        context.setAttribute("key", "value");

        context.removeAttribute("key");

        assertNull(context.getAttribute("key"));
    }

    @Test
    void shouldEnumerateAttributeNames() {
        context.setAttribute("a", 1);
        context.setAttribute("b", 2);

        var names = collectNames(context.getAttributeNames());

        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
        assertEquals(2, names.size());
    }

    // --- Init parameter methods ---

    @Test
    void shouldReturnNullForUnknownInitParameter() {
        assertNull(context.getInitParameter("unknown"));
    }

    @Test
    void shouldSetInitParameterAndReturnTrue() {
        assertTrue(context.setInitParameter("key", "value"));

        assertEquals("value", context.getInitParameter("key"));
    }

    @Test
    void shouldReturnFalseWhenInitParameterAlreadySet() {
        context.setInitParameter("key", "value");

        assertFalse(context.setInitParameter("key", "other"));
        assertEquals("value", context.getInitParameter("key"));
    }

    @Test
    void shouldEnumerateInitParameterNames() {
        context.setInitParameter("x", "1");
        context.setInitParameter("y", "2");

        var names = collectNames(context.getInitParameterNames());

        assertTrue(names.contains("x"));
        assertTrue(names.contains("y"));
        assertEquals(2, names.size());
    }

    // --- Servlet registration ---

    @Test
    void shouldAddServletByClassName() {
        var registration = context.addServlet("myServlet", "com.example.MyServlet");

        assertNotNull(registration);
        assertInstanceOf(ServletRegistration.Dynamic.class, registration);
        assertEquals("myServlet", registration.getName());
        assertEquals("com.example.MyServlet", registration.getClassName());
    }

    @Test
    void shouldAddServletByInstance() {
        Servlet servlet = new StubServlet();

        var registration = context.addServlet("myServlet", servlet);

        assertNotNull(registration);
        assertEquals("myServlet", registration.getName());
        assertEquals(StubServlet.class.getName(), registration.getClassName());
    }

    @Test
    void shouldAddServletByClass() {
        var registration = context.addServlet("myServlet", StubServlet.class);

        assertNotNull(registration);
        assertEquals("myServlet", registration.getName());
        assertEquals(StubServlet.class.getName(), registration.getClassName());
    }

    @Test
    void shouldGetServletRegistrationByName() {
        context.addServlet("myServlet", "com.example.MyServlet");

        var registration = context.getServletRegistration("myServlet");

        assertNotNull(registration);
        assertEquals("myServlet", registration.getName());
    }

    @Test
    void shouldReturnNullForUnknownServletRegistration() {
        assertNull(context.getServletRegistration("unknown"));
    }

    @Test
    void shouldReturnAllServletRegistrations() {
        context.addServlet("s1", "com.example.S1");
        context.addServlet("s2", "com.example.S2");

        Map<String, ? extends ServletRegistration> registrations = context.getServletRegistrations();

        assertEquals(2, registrations.size());
        assertTrue(registrations.containsKey("s1"));
        assertTrue(registrations.containsKey("s2"));
    }

    @Test
    void shouldReturnUnmodifiableServletRegistrations() {
        context.addServlet("s1", "com.example.S1");

        var registrations = context.getServletRegistrations();

        assertThrows(UnsupportedOperationException.class, () -> registrations.put("s2", null));
    }

    // --- Servlet registration init parameters ---

    @Test
    void shouldSetAndGetServletRegistrationInitParameter() {
        var registration = context.addServlet("s", "com.example.S");

        assertTrue(registration.setInitParameter("p1", "v1"));
        assertEquals("v1", registration.getInitParameter("p1"));
    }

    @Test
    void shouldReturnFalseForDuplicateServletRegistrationInitParameter() {
        var registration = context.addServlet("s", "com.example.S");
        registration.setInitParameter("p1", "v1");

        assertFalse(registration.setInitParameter("p1", "v2"));
        assertEquals("v1", registration.getInitParameter("p1"));
    }

    @Test
    void shouldReturnNullForUnknownServletRegistrationInitParameter() {
        var registration = context.addServlet("s", "com.example.S");

        assertNull(registration.getInitParameter("unknown"));
    }

    @Test
    void shouldBulkSetServletRegistrationInitParameters() {
        var registration = context.addServlet("s", "com.example.S");
        registration.setInitParameter("existing", "old");

        var conflicts = registration.setInitParameters(Map.of("existing", "new", "fresh", "value"));

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.contains("existing"));
        assertEquals("old", registration.getInitParameter("existing"));
        assertEquals("value", registration.getInitParameter("fresh"));
    }

    @Test
    void shouldReturnUnmodifiableServletRegistrationInitParameters() {
        var registration = context.addServlet("s", "com.example.S");
        registration.setInitParameter("p1", "v1");

        var params = registration.getInitParameters();

        assertThrows(UnsupportedOperationException.class, () -> params.put("p2", "v2"));
    }

    // --- Servlet registration mappings ---

    @Test
    void shouldAddAndGetServletMappings() {
        var registration = context.addServlet("s", "com.example.S");

        registration.addMapping("/a", "/b");

        var mappings = registration.getMappings();
        assertEquals(2, mappings.size());
        assertTrue(mappings.contains("/a"));
        assertTrue(mappings.contains("/b"));
    }

    @Test
    void shouldReturnEmptyMappingsInitially() {
        var registration = context.addServlet("s", "com.example.S");

        assertTrue(registration.getMappings().isEmpty());
    }

    // --- Servlet registration stub methods ---

    @Test
    void shouldReturnNullRunAsRole() {
        var registration = context.addServlet("s", "com.example.S");

        assertNull(registration.getRunAsRole());
    }

    @Test
    void shouldAcceptSetLoadOnStartup() {
        var registration = context.addServlet("s", "com.example.S");

        assertDoesNotThrow(() -> registration.setLoadOnStartup(1));
    }

    @Test
    void shouldAcceptSetServletSecurity() {
        var registration = context.addServlet("s", "com.example.S");

        var result = registration.setServletSecurity(new jakarta.servlet.ServletSecurityElement());

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldAcceptSetMultipartConfig() {
        var registration = context.addServlet("s", "com.example.S");

        assertDoesNotThrow(() -> registration.setMultipartConfig(new jakarta.servlet.MultipartConfigElement("/tmp")));
    }

    @Test
    void shouldAcceptSetRunAsRole() {
        var registration = context.addServlet("s", "com.example.S");

        assertDoesNotThrow(() -> registration.setRunAsRole("admin"));
    }

    @Test
    void shouldAcceptSetAsyncSupported() {
        var registration = context.addServlet("s", "com.example.S");

        assertDoesNotThrow(() -> registration.setAsyncSupported(true));
    }

    // --- Filter registration ---

    @Test
    void shouldAddFilterByClassName() {
        var registration = context.addFilter("myFilter", "com.example.MyFilter");

        assertNotNull(registration);
        assertInstanceOf(FilterRegistration.Dynamic.class, registration);
        assertEquals("myFilter", registration.getName());
        assertEquals("com.example.MyFilter", registration.getClassName());
    }

    @Test
    void shouldAddFilterByInstance() {
        Filter filter = new StubFilter();

        var registration = context.addFilter("myFilter", filter);

        assertNotNull(registration);
        assertEquals("myFilter", registration.getName());
        assertEquals(StubFilter.class.getName(), registration.getClassName());
    }

    @Test
    void shouldAddFilterByClass() {
        var registration = context.addFilter("myFilter", StubFilter.class);

        assertNotNull(registration);
        assertEquals("myFilter", registration.getName());
        assertEquals(StubFilter.class.getName(), registration.getClassName());
    }

    @Test
    void shouldGetFilterRegistrationByName() {
        context.addFilter("myFilter", "com.example.MyFilter");

        var registration = context.getFilterRegistration("myFilter");

        assertNotNull(registration);
        assertEquals("myFilter", registration.getName());
    }

    @Test
    void shouldReturnNullForUnknownFilterRegistration() {
        assertNull(context.getFilterRegistration("unknown"));
    }

    @Test
    void shouldReturnAllFilterRegistrations() {
        context.addFilter("f1", "com.example.F1");
        context.addFilter("f2", "com.example.F2");

        Map<String, ? extends FilterRegistration> registrations = context.getFilterRegistrations();

        assertEquals(2, registrations.size());
        assertTrue(registrations.containsKey("f1"));
        assertTrue(registrations.containsKey("f2"));
    }

    @Test
    void shouldReturnUnmodifiableFilterRegistrations() {
        context.addFilter("f1", "com.example.F1");

        var registrations = context.getFilterRegistrations();

        assertThrows(UnsupportedOperationException.class, () -> registrations.put("f2", null));
    }

    // --- Filter registration init parameters ---

    @Test
    void shouldSetAndGetFilterRegistrationInitParameter() {
        var registration = context.addFilter("f", "com.example.F");

        assertTrue(registration.setInitParameter("p1", "v1"));
        assertEquals("v1", registration.getInitParameter("p1"));
    }

    @Test
    void shouldReturnUnmodifiableFilterRegistrationInitParameters() {
        var registration = context.addFilter("f", "com.example.F");
        registration.setInitParameter("p1", "v1");

        var params = registration.getInitParameters();

        assertThrows(UnsupportedOperationException.class, () -> params.put("p2", "v2"));
    }

    // --- Filter registration stub methods ---

    @Test
    void shouldAcceptAddMappingForServletNames() {
        var registration = context.addFilter("f", "com.example.F");

        assertDoesNotThrow(() -> registration.addMappingForServletNames(null, false, "servletA"));
    }

    @Test
    void shouldReturnEmptyServletNameMappings() {
        var registration = context.addFilter("f", "com.example.F");

        assertTrue(registration.getServletNameMappings().isEmpty());
    }

    @Test
    void shouldAcceptAddMappingForUrlPatterns() {
        var registration = context.addFilter("f", "com.example.F");

        assertDoesNotThrow(() -> registration.addMappingForUrlPatterns(null, false, "/api/*"));
    }

    @Test
    void shouldReturnEmptyUrlPatternMappings() {
        var registration = context.addFilter("f", "com.example.F");

        assertTrue(registration.getUrlPatternMappings().isEmpty());
    }

    @Test
    void shouldStoreUrlPatternMappings() {
        var registration = context.addFilter("f", new StubFilter());

        registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/api/*", "/admin/*");

        assertTrue(registration.getUrlPatternMappings().containsAll(List.of("/api/*", "/admin/*")));
    }

    @Test
    void shouldDefaultDispatcherTypesToRequestWhenNullPassed() {
        var registration = context.addFilter("f", new StubFilter());

        registration.addMappingForUrlPatterns(null, false, "/*");

        var registered = context.getRegisteredFilters();
        assertEquals(1, registered.size());
        assertTrue(registered.get(0).dispatcherTypes().contains(DispatcherType.REQUEST));
    }

    // --- Executable filter registrations (getRegisteredFilters) ---

    @Test
    void shouldRetainFilterInstanceInRegisteredFilters() {
        Filter filter = new StubFilter();
        var registration = context.addFilter("myFilter", filter);
        registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");

        var registered = context.getRegisteredFilters();

        assertEquals(1, registered.size());
        assertEquals("myFilter", registered.get(0).name());
        assertEquals(filter, registered.get(0).filter());
    }

    @Test
    void shouldPreserveRegistrationOrderInRegisteredFilters() {
        context.addFilter("first", new StubFilter());
        context.addFilter("second", new StubFilter());

        var registered = context.getRegisteredFilters();

        assertEquals(List.of("first", "second"), registered.stream().map(RegisteredFilter::name).toList());
    }

    @Test
    void shouldExcludeClassNameOnlyRegistrationsFromRegisteredFilters() {
        context.addFilter("instanceFilter", new StubFilter());
        context.addFilter("classNameFilter", "com.example.MyFilter");
        context.addFilter("classFilter", StubFilter.class);

        var registered = context.getRegisteredFilters();

        assertEquals(1, registered.size());
        assertEquals("instanceFilter", registered.get(0).name());
    }

    // --- Resource methods ---
    //
    // The document root is the classpath's META-INF/resources/ tree, mirroring what a servlet
    // container exposes for a jar-packaged application. Fixtures live in src/test/resources:
    // META-INF/resources/doc-root.txt, META-INF/resources/sub/nested.txt, static/only-in-static.txt.

    @Test
    void shouldResolveResourceFromDocumentRoot() throws MalformedURLException {
        assertNotNull(context.getResource("/doc-root.txt"));
    }

    @Test
    void shouldReturnNullForResourceMissingFromDocumentRoot() throws MalformedURLException {
        assertNull(context.getResource("/no-such-file.txt"));
    }

    /**
     * Boot's other static locations are not part of the document root — {@code ResourceHttpRequestHandler}
     * serves those off the classpath itself, never through the ServletContext. Tomcat returns null here
     * too; pinning it keeps a future change from quietly widening the root and diverging from the spec.
     */
    @Test
    void shouldNotResolveClasspathStaticAsDocumentRootResource() throws MalformedURLException {
        assertNull(context.getResource("/static/only-in-static.txt"));
        assertNull(context.getResource("/only-in-static.txt"));
    }

    @Test
    void shouldThrowForResourcePathWithoutLeadingSlash() {
        assertThrows(MalformedURLException.class, () -> context.getResource("doc-root.txt"));
    }

    /**
     * A resource path must not escape the document root. The fixture outside-document-root.txt sits on
     * the classpath but outside META-INF/resources/, so a traversal that resolved would reach it — and
     * on a real deployment would expose application classes and configuration.
     */
    @Test
    void shouldNotEscapeDocumentRootViaParentTraversal() throws Exception {
        assertNull(context.getResource("/../outside-document-root.txt"));
        assertNull(context.getResourceAsStream("/../outside-document-root.txt"));
        assertNull(context.getResource("/sub/../../outside-document-root.txt"));
    }

    @Test
    void shouldReadResourceContentAsStream() throws IOException {
        try (InputStream stream = context.getResourceAsStream("/doc-root.txt")) {
            assertNotNull(stream);
            assertEquals("document root resource\n", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Unlike getResource, the spec has getResourceAsStream report a bad path with null, not an exception.
     */
    @Test
    void shouldReturnNullStreamForResourcePathWithoutLeadingSlash() {
        assertNull(context.getResourceAsStream("doc-root.txt"));
    }

    @Test
    void shouldListImmediateChildrenOfDocumentRoot() {
        assertEquals(Set.of("/doc-root.txt", "/my file.txt", "/sub/"), context.getResourcePaths("/"));
    }

    /**
     * The test classpath is a plain directory, so the exploded branch of the listing is the only one the
     * other tests reach. Packaged applications hit the jar branch instead, which reads names out of the
     * jar index rather than the filesystem — build a real jar so that path is covered too.
     */
    @Test
    void shouldListImmediateChildrenInsideJar(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("resources.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/resources/"));
            out.putNextEntry(new ZipEntry("META-INF/resources/packaged.txt"));
            out.write("packaged\n".getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry("META-INF/resources/assets/"));
            out.putNextEntry(new ZipEntry("META-INF/resources/assets/app.js"));
            out.write("console.log(1)\n".getBytes(StandardCharsets.UTF_8));
        }

        try (URLClassLoader jarLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            DefaultNettyServletContext jarContext = new DefaultNettyServletContext(jarLoader);

            assertEquals(Set.of("/packaged.txt", "/assets/"), jarContext.getResourcePaths("/"));
            assertEquals(Set.of("/assets/app.js"), jarContext.getResourcePaths("/assets"));
            assertNotNull(jarContext.getResource("/packaged.txt"));
        }
    }

    @Test
    void shouldListChildrenOfSubdirectory() {
        assertEquals(Set.of("/sub/nested.txt"), context.getResourcePaths("/sub"));
    }

    @Test
    void shouldReturnNullResourcePathsForUnknownDirectory() {
        assertNull(context.getResourcePaths("/no-such-directory"));
    }

    /**
     * The paths getResourcePaths hands back are ordinary context resource paths, so feeding one back
     * into getResource must find the resource it just reported. That round trip breaks if the listing
     * leaks percent-encoding from the underlying URL: the fixture "my file.txt" would come back as
     * "my%20file.txt", which names no resource.
     */
    @Test
    void shouldListPathsThatCanBeResolvedBack() throws MalformedURLException {
        for (String path : context.getResourcePaths("/")) {
            if (!path.endsWith("/")) {
                assertNotNull(context.getResource(path), () -> "listed path did not resolve: " + path);
            }
        }
    }

    /**
     * A directory name is a literal, not a glob. Were it spliced into an Ant pattern, the "*" here would
     * match the real "sub" directory and report its children as though "s*b" existed.
     */
    @Test
    void shouldNotTreatResourcePathAsGlobPattern() {
        assertNull(context.getResourcePaths("/s*b"));
    }

    /**
     * The document root lives on the classpath, which has no filesystem path. Null is what the spec
     * prescribes and what Tomcat returns for a jar-packaged application.
     */
    @Test
    void shouldReturnNullForGetRealPath() {
        assertNull(context.getRealPath("/doc-root.txt"));
    }

    // --- MIME types ---
    //
    // ResourceHttpRequestHandler.getMediaType calls getMimeType for every static resource it serves,
    // so this must resolve rather than throw.

    @Test
    void shouldResolveMimeTypeFromFileExtension() {
        assertEquals("text/html", context.getMimeType("index.html"));
        assertEquals("text/plain", context.getMimeType("doc-root.txt"));
    }

    @Test
    void shouldReturnNullMimeTypeForUnknownExtension() {
        assertNull(context.getMimeType("archive.unknownext"));
    }

    // --- Class loader ---

    @Test
    void shouldExposeTheInjectedClassLoader() {
        ClassLoader classLoader = new ClassLoader() {
        };
        assertEquals(classLoader, new DefaultNettyServletContext(classLoader).getClassLoader());
    }

    // --- Simple getters ---

    @Test
    void shouldReturnEmptyContextPath() {
        assertEquals("", context.getContextPath());
    }

    @Test
    void shouldReturnServletContextName() {
        assertEquals("NettyServletContext", context.getServletContextName());
    }

    @Test
    void shouldReturnMajorVersion() {
        assertEquals(6, context.getMajorVersion());
    }

    @Test
    void shouldReturnMinorVersion() {
        assertEquals(0, context.getMinorVersion());
    }

    @Test
    void shouldReturnEffectiveMajorVersion() {
        assertEquals(6, context.getEffectiveMajorVersion());
    }

    @Test
    void shouldReturnEffectiveMinorVersion() {
        assertEquals(0, context.getEffectiveMinorVersion());
    }

    @Test
    void shouldReturnServerInfo() {
        assertEquals("Netty-Loom", context.getServerInfo());
    }

    @Test
    void shouldReturnClassLoader() {
        assertNotNull(context.getClassLoader());
    }

    private static List<String> collectNames(Enumeration<String> enumeration) {
        var names = new java.util.ArrayList<String>();
        enumeration.asIterator().forEachRemaining(names::add);
        return names;
    }

    // --- Stub types for testing ---

    private static class StubServlet implements Servlet {
        @Override public void init(jakarta.servlet.ServletConfig config) {}
        @Override public jakarta.servlet.ServletConfig getServletConfig() { return null; }
        @Override public void service(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {}
        @Override public String getServletInfo() { return null; }
        @Override public void destroy() {}
    }

    private static class StubFilter implements Filter {
        @Override public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                                       jakarta.servlet.FilterChain chain) {}
    }
}
