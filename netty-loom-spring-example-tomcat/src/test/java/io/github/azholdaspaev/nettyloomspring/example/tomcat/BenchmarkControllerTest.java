package io.github.azholdaspaev.nettyloomspring.example.tomcat;

import io.github.azholdaspaev.nettyloomspring.example.tomcat.BenchmarkController.WorkResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.net.HttpCookie;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AutoConfigureRestTestClient
@SpringBootTest(
    classes = TomcatExampleApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class BenchmarkControllerTest {

    private static final String SESSION_COOKIE = "JSESSIONID";

    /**
     * Spring Security renders the hidden field as {@code <input name="_csrf" type="hidden"
     * value="..."/>} — {@code type} sits between the two attributes, so matching {@code name}
     * immediately followed by {@code value} silently finds nothing. The k6 script uses the same
     * pattern.
     */
    private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    @Autowired
    private RestTestClient restTestClient;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void pingReturnsPong() {
        restTestClient.get().uri("/ping")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("pong");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void workReturnsJsonAfterSimulatedBlockingCall() {
        restTestClient.get().uri("/work")
            .exchange()
            .expectStatus().isOk()
            .expectBody(WorkResponse.class).isEqualTo(new WorkResponse("ok", 50));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void workSecuredRedirectsToLoginWhenUnauthenticated() {
        restTestClient.get().uri("/work-secured")
            .exchange()
            .expectStatus().isFound()
            .expectHeader().valueMatches("Location", ".*/login$");
    }

    /**
     * The executable spec for {@code k6/high-concurrency-secured.js}: the same three exchanges the
     * load script's per-VU login performs. It is what proves the whole Security filter chain — form
     * body parsing, CSRF, session creation and id rotation — survives the servlet container.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void workSecuredReturnsJsonAfterFormLoginOnTheRotatedSessionCookie() {
        var loginPage = restTestClient.get().uri("/login")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult();

        String preLoginSessionId = sessionIdFrom(loginPage.getResponseHeaders());
        // Asserted, not assumed: if the login page created no session the rotation check below
        // would compare null against an id and pass vacuously.
        assertNotNull(preLoginSessionId, "the login page must create the CSRF-token session");
        String csrfToken = csrfTokenFrom(loginPage.getResponseBody());

        var login = restTestClient.post().uri("/login")
            .header(HttpHeaders.COOKIE, SESSION_COOKIE + "=" + preLoginSessionId)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("username=bench&password=bench&_csrf=" + csrfToken)
            .exchange()
            .expectStatus().isFound()
            .expectBody().returnResult();

        String postLoginSessionId = sessionIdFrom(login.getResponseHeaders());
        assertNotNull(postLoginSessionId, "a successful login must emit a session cookie");
        // Session-fixation defence (CWE-384). Also load-bearing for the k6 script: the id the VU
        // authenticated with is not the one it must carry afterwards, so a bridge that dropped the
        // rotated cookie would send every subsequent request back to /login.
        assertNotEquals(preLoginSessionId, postLoginSessionId,
            "login must rotate the session id");

        // Twice on the same cookie, not once. The load scenario authenticates a virtual user once and
        // then rides that session for the whole plateau, so a session that authenticates a single
        // request and then lapses would leave every later request redirecting to /login — cheap,
        // fast, and indistinguishable from a win in the aggregate numbers.
        expectWorkSecuredOk(postLoginSessionId);
        expectWorkSecuredOk(postLoginSessionId);
    }

    private void expectWorkSecuredOk(String sessionId) {
        restTestClient.get().uri("/work-secured")
            .header(HttpHeaders.COOKIE, SESSION_COOKIE + "=" + sessionId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(WorkResponse.class).isEqualTo(new WorkResponse("ok", 50));
    }

    /**
     * {@code RestTestClient} does not persist cookies across exchanges, so the session cookie is
     * carried by hand. Parsed with {@link HttpCookie} rather than a substring split — a hand-rolled
     * cookie grammar is not worth saving two lines. The starter's test sources own an equivalent
     * helper ({@code ResponseCookies}), but sharing it would need a {@code java-test-fixtures}
     * publication, and consuming that from the Tomcat example would put this library's starter on
     * the control target's classpath — the one coupling the three-way benchmark exists to avoid.
     */
    private static String sessionIdFrom(HttpHeaders headers) {
        List<String> setCookies = headers.getOrEmpty(HttpHeaders.SET_COOKIE);
        return setCookies.stream()
            .flatMap(header -> HttpCookie.parse(header).stream())
            .filter(cookie -> cookie.getName().equals(SESSION_COOKIE))
            .map(HttpCookie::getValue)
            .findFirst()
            .orElse(null);
    }

    private static String csrfTokenFrom(String loginPageHtml) {
        assertNotNull(loginPageHtml, "the generated login page must have a body");
        Matcher matcher = CSRF_INPUT.matcher(loginPageHtml);
        assertTrue(matcher.find(), () -> "no CSRF hidden input in the login page: " + loginPageHtml);
        return matcher.group(1);
    }
}
