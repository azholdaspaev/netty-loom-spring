package io.github.azholdaspaev.nettyloomspring.integration.support;

import io.github.azholdaspaev.nettyloomspring.integration.security.SecurityConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class FormLogin {

    public static final String SESSION_COOKIE = "JSESSIONID";

    private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    private FormLogin() {
    }

    public record Login(String preLoginSessionId, String postLoginSessionId) {
    }

    public static Login logIn(RestTestClient restTestClient) {
        var loginPage = restTestClient.get().uri("/login")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult();
        String preLoginSessionId = ResponseCookies.valueOf(loginPage.getResponseHeaders(), SESSION_COOKIE);

        var login = restTestClient.post().uri("/login")
            .header(HttpHeaders.COOKIE, SESSION_COOKIE + "=" + preLoginSessionId)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("username=" + SecurityConfig.USERNAME
                + "&password=" + SecurityConfig.PASSWORD
                + "&_csrf=" + encode(csrfTokenFrom(loginPage.getResponseBody())))
            .exchange()
            .expectStatus().isFound()
            .expectBody().returnResult();
        return new Login(preLoginSessionId, ResponseCookies.valueOf(login.getResponseHeaders(), SESSION_COOKIE));
    }

    public static String encode(String formValue) {
        return URLEncoder.encode(formValue, StandardCharsets.UTF_8);
    }

    private static String csrfTokenFrom(String loginPageHtml) {
        assertNotNull(loginPageHtml, "the generated login page must have a body");
        Matcher matcher = CSRF_INPUT.matcher(loginPageHtml);
        assertTrue(matcher.find(), () -> "no CSRF hidden input in the login page: " + loginPageHtml);
        return matcher.group(1);
    }
}
