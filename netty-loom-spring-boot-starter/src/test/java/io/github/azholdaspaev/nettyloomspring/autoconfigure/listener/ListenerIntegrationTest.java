package io.github.azholdaspaev.nettyloomspring.autoconfigure.listener;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app.ListenerTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app.RecordingListener;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.ResponseCookies;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettySessionCookieConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end delivery of the container-registered servlet listeners over a real Netty server (issue #17).
 *
 * <p>The listener is registered through Boot's {@code ServletListenerRegistrationBean}, so this is also
 * the regression test for the original symptom: that route calls {@code ServletContext.addListener},
 * which threw {@code UnsupportedOperationException} out of {@code onStartup} and aborted startup
 * outright. If registration ever breaks again, every test in this class fails at context load.
 *
 * <p>{@code RestTestClient} does not persist cookies, so a test that needs two requests on one session
 * captures the {@code JSESSIONID} and resends it explicitly.
 */
@AutoConfigureRestTestClient
@SpringBootTest(
    classes = ListenerTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ListenerIntegrationTest {

    private static final String SESSION_COOKIE = NettySessionCookieConfig.DEFAULT_NAME;

    @Autowired
    private RestTestClient restTestClient;

    @Autowired
    private RecordingListener listener;

    @BeforeEach
    void resetCounters() {
        listener.reset();
    }

    private void assertFired(String event, int expected) {
        assertEquals(expected, listener.countOf(event),
            () -> event + " fired " + listener.countOf(event) + " time(s); all events seen: "
                + listener.snapshot());
    }

    /** Runs {@code uri}, returning the session id its {@code Set-Cookie} carried, if any. */
    private String call(String uri) {
        var result = restTestClient.get().uri(uri)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult();
        return ResponseCookies.valueOf(result.getResponseHeaders(), SESSION_COOKIE);
    }

    private void callWithSession(String uri, String sessionId) {
        restTestClient.get().uri(uri)
            .cookie(SESSION_COOKIE, sessionId)
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void everyRequestIsBracketedByTheRequestListener() {
        call("/listener/ping");

        assertFired("requestInitialized", 1);
        assertFired("requestDestroyed", 1);
    }

    @Test
    void creatingASessionFiresSessionCreated() {
        assertNotNull(call("/listener/session/create"), "creating a session must emit a JSESSIONID cookie");

        assertFired("sessionCreated", 1);
        assertFired("sessionDestroyed", 0);
    }

    @Test
    void invalidatingASessionFiresSessionDestroyed() {
        String sessionId = call("/listener/session/create");
        listener.reset();

        callWithSession("/listener/session/invalidate", sessionId);

        assertFired("sessionDestroyed", 1);
        assertFired("sessionCreated", 0);
    }

    @Test
    void sessionAttributeMutationsFireAddedReplacedAndRemoved() {
        call("/listener/session/attributes");

        assertFired("sessionAttributeAdded:value", 1);
        assertFired("sessionAttributeReplaced:value", 1);
        assertFired("sessionAttributeRemoved:value", 1);
    }

    @Test
    void requestAttributeMutationsFireAddedReplacedAndRemoved() {
        // Scoped to the fixture's own attribute: DispatcherServlet sets more than a dozen of its own on
        // every dispatch, and each of those notifies too -- correctly, and exactly as it does on Tomcat.
        call("/listener/request/attributes");

        assertFired("requestAttributeAdded:stage", 1);
        assertFired("requestAttributeReplaced:stage", 1);
        assertFired("requestAttributeRemoved:stage", 1);
    }

    @Test
    void springsOwnRequestAttributesAlsoReachTheListener() {
        // Not incidental: a container that only announced application-set attributes would be a subtly
        // different container. DispatcherServlet always publishes its WebApplicationContext this way.
        call("/listener/ping");

        assertTrue(listener.countOf("requestAttributeAdded") > 0,
            "the framework's own request attributes must notify like any other; saw " + listener.snapshot());
    }

    @Test
    void rotatingTheSessionIdFiresSessionIdChanged() {
        // changeSessionId() is what Spring Security calls on every authentication, so this is the event a
        // session registry needs to keep tracking a user across the login boundary.
        String sessionId = call("/listener/session/create");
        listener.reset();

        callWithSession("/listener/session/rotate", sessionId);

        assertFired("sessionIdChanged", 1);
        assertFired("sessionDestroyed", 0);
    }
}
