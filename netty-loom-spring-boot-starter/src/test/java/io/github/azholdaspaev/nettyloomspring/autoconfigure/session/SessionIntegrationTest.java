package io.github.azholdaspaev.nettyloomspring.autoconfigure.session;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.session.app.SessionController;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.session.app.SessionTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.ResponseCookies;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettySessionCookieConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.net.HttpCookie;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end session round-trips over a real Netty server (issue #13).
 *
 * <p>{@code RestTestClient} does not persist cookies across exchanges, so every test captures the
 * {@code Set-Cookie} it wants to carry and resends it explicitly -- which is also what makes the
 * assertions about <em>when</em> a cookie is and is not emitted meaningful.
 */
@AutoConfigureRestTestClient
@SpringBootTest(
    classes = SessionTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SessionIntegrationTest {

    private static final String SESSION_COOKIE = NettySessionCookieConfig.DEFAULT_NAME;

    /** Well-formed as an id the server could have minted -- 32 uppercase hex -- but naming no session. */
    private static final String UNKNOWN_SESSION_ID = "0123456789ABCDEF0123456789ABCDEF";

    @Autowired
    private RestTestClient restTestClient;

    private static String sessionIdFrom(HttpHeaders headers) {
        return ResponseCookies.valueOf(headers, SESSION_COOKIE);
    }

    private String createSessionWith(String value) {
        var result = restTestClient.get().uri("/session/set?value=" + value)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult();

        String cookieId = sessionIdFrom(result.getResponseHeaders());
        assertNotNull(cookieId, "creating a session must emit a JSESSIONID cookie");
        assertEquals(result.getResponseBody(), cookieId,
            "the cookie must carry the id the server reports for the session");
        return cookieId;
    }

    /** The acceptance criterion for issue #13. */
    @Test
    void anAttributeSetOnOneRequestIsReadableOnTheNextUsingTheReturnedCookie() {
        String sessionId = createSessionWith("hello");

        restTestClient.get().uri("/session/get")
            .cookie(SESSION_COOKIE, sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello");
    }

    @Test
    void aRequestWithoutTheCookieSeesNoSession() {
        createSessionWith("hello");

        restTestClient.get().uri("/session/get")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo(SessionController.NONE);
    }

    @Test
    void aStaleDuplicateSessionCookieDoesNotMaskTheLiveOne() {
        // Issue #91, over the wire: what this adds over the unit tests is that a duplicated cookie name
        // survives the real socket and HttpServerCodec un-merged and in order. Hence the raw header
        // rather than two .cookie(...) calls -- whether RestTestClient's cookie map serialises a
        // duplicated name into one header is undocumented, and that is exactly the premise under test.
        String sessionId = createSessionWith("hello");

        restTestClient.get().uri("/session/get")
            .header(HttpHeaders.COOKIE,
                SESSION_COOKIE + "=" + UNKNOWN_SESSION_ID + "; " + SESSION_COOKIE + "=" + sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello");
    }

    @Test
    void aStatelessRequestGetsNoSetCookie() {
        restTestClient.get().uri("/session/stateless")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    void anEstablishedSessionIsNotReIssuedOnEveryResponse() {
        String sessionId = createSessionWith("hello");

        restTestClient.get().uri("/session/get")
            .cookie(SESSION_COOKIE, sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    void theSessionCookieIsHttpOnlyAndPathScopedAndCarriesNoMaxAge() {
        var result = restTestClient.get().uri("/session/set?value=hello")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult();

        String setCookie = result.getResponseHeaders().get(HttpHeaders.SET_COOKIE).getFirst();
        HttpCookie parsed = HttpCookie.parse(setCookie).getFirst();
        assertTrue(setCookie.contains("HTTPOnly"), "Actual: " + setCookie);
        // Parsed, not substring-matched: "Path=/" is a prefix of every path, so contains() would hold
        // whatever the container actually emitted.
        assertEquals("/", parsed.getPath(), "Actual: " + setCookie);
        assertEquals(-1, parsed.getMaxAge(), "A browser-session cookie carries no Max-Age: " + setCookie);
    }

    @Test
    void aSessionIsNewOnlyForTheRequestThatCreatedIt() {
        var result = restTestClient.get().uri("/session/isnew")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("true")
            .returnResult();

        restTestClient.get().uri("/session/isnew")
            .cookie(SESSION_COOKIE, sessionIdFrom(result.getResponseHeaders()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("false");
    }

    @Test
    void anInvalidatedSessionIsGoneAndTheNextGetSessionIssuesANewId() {
        String sessionId = createSessionWith("hello");

        restTestClient.get().uri("/session/invalidate")
            .cookie(SESSION_COOKIE, sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("invalidated");

        // The old id resolves to nothing...
        restTestClient.get().uri("/session/id")
            .cookie(SESSION_COOKIE, sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo(SessionController.NONE);

        // ...and asking for a session again mints a different one.
        var recreated = restTestClient.get().uri("/session/set?value=again")
            .cookie(SESSION_COOKIE, sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult();

        assertNotEquals(sessionId, sessionIdFrom(recreated.getResponseHeaders()));
    }

    @Test
    void anUnknownSessionIdIsReportedAsRequestedButInvalid() {
        // SessionManagementFilter keys on exactly this pair to detect an expired session.
        restTestClient.get().uri("/session/requested-id")
            .cookie(SESSION_COOKIE, UNKNOWN_SESSION_ID)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo(UNKNOWN_SESSION_ID + ":false");
    }

    @Test
    void noSessionCookieMeansNoRequestedSessionId() {
        // "null", not "" -- an empty string is non-null and would make SessionManagementFilter run its
        // invalid-session strategy on every stateless request.
        restTestClient.get().uri("/session/requested-id")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("null:false");
    }

    @Test
    void aRequestCarryingOtherCookiesButNoSessionCookieCreatesNoSession() {
        var result = restTestClient.get().uri("/session/id")
            .cookie("theme", "dark")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo(SessionController.NONE)
            .returnResult();

        assertNull(sessionIdFrom(result.getResponseHeaders()));
    }

    // --- Session fixation (issue #52) ---

    @Test
    void changeSessionIdRotatesTheIdAndReIssuesTheCookie() {
        String originalId = createSessionWith("hello");

        var rotated = restTestClient.get().uri("/session/rotate")
            .cookie(SESSION_COOKIE, originalId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult();

        String newId = rotated.getResponseBody();
        assertNotEquals(originalId, newId, "the id must actually rotate, or fixation protection is a no-op");
        assertEquals(newId, sessionIdFrom(rotated.getResponseHeaders()), "the client must be told the new id");

        // The rotation keeps the session's contents...
        restTestClient.get().uri("/session/get")
            .cookie(SESSION_COOKIE, newId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello");

        // ...and the pre-rotation id, which an attacker may have planted, is dead.
        restTestClient.get().uri("/session/id")
            .cookie(SESSION_COOKIE, originalId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo(SessionController.NONE);
    }

    // --- Cookie emission across a commit: the regression these two tests exist for ---

    @Test
    void aSessionCreatedBeforeSendRedirectStillEmitsItsCookie() {
        // RedirectView saves the flash map (creating the session), then commits via sendRedirect. Since
        // addCookie is ignored after a commit, emitting the cookie any later than creation loses it.
        var result = restTestClient.get().uri("/session/flash")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectBody(String.class).returnResult();

        assertNotNull(sessionIdFrom(result.getResponseHeaders()),
            "the redirect response must still carry the session cookie");
    }

    @Test
    void flashAttributesSurviveARedirect() {
        var redirect = restTestClient.get().uri("/session/flash")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectBody(String.class).returnResult();

        String sessionId = sessionIdFrom(redirect.getResponseHeaders());
        assertNotNull(sessionId);

        restTestClient.get().uri("/session/flash-target")
            .cookie(SESSION_COOKIE, sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("hello");
    }

    @Test
    void aFlashRedirectWithoutTheCookieLosesTheFlashAttribute() {
        restTestClient.get().uri("/session/flash").exchange().expectStatus().is3xxRedirection();

        restTestClient.get().uri("/session/flash-target")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo(SessionController.NONE);
    }
}
