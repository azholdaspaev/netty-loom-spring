package io.github.azholdaspaev.nettyloomspring.autoconfigure.requestid.app;

import org.springframework.boot.web.error.ErrorPage;
import org.springframework.boot.web.error.ErrorPageRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration
public class RequestIdentityTestConfig {

    @Bean
    ErrorPageRegistrar identityErrorPageRegistrar() {
        return registry -> registry.addErrorPages(
            new ErrorPage(HttpStatus.INTERNAL_SERVER_ERROR, RequestIdentityController.DISPATCHED));
    }
}
