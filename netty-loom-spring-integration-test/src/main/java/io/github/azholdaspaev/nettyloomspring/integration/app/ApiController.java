package io.github.azholdaspaev.nettyloomspring.integration.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    public static final String TEAM_CLAIM = "team";

    @GetMapping("/me")
    public Map<String, String> describeCaller(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("sub", jwt.getSubject(), TEAM_CLAIM, jwt.getClaimAsString(TEAM_CLAIM));
    }

    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    public String administer() {
        return "admin";
    }
}
