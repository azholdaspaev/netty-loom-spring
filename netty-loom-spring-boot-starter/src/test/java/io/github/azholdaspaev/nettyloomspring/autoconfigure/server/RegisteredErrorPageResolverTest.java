package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorPage;
import org.springframework.http.HttpStatus;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegisteredErrorPageResolverTest {

    @Test
    void theGlobalPageAnswersAnyStatus() {
        var resolver = new RegisteredErrorPageResolver(List.of(new ErrorPage("/error")));

        assertEquals("/error", resolver.resolve(404, null, null));
        var failure = new IllegalStateException();
        assertEquals("/error", resolver.resolve(500, failure, failure));
    }

    @Test
    void aStatusSpecificPageBeatsTheGlobalOne() {
        var resolver = new RegisteredErrorPageResolver(List.of(
            new ErrorPage("/error"),
            new ErrorPage(HttpStatus.NOT_FOUND, "/404")));

        assertEquals("/404", resolver.resolve(404, null, null));
        assertEquals("/error", resolver.resolve(500, null, null));
    }

    @Test
    void anExceptionPageIsFoundByWalkingSuperclasses() {
        var resolver = new RegisteredErrorPageResolver(List.of(new ErrorPage(IOException.class, "/io")));

        var failure = new FileNotFoundException();
        assertEquals("/io", resolver.resolve(500, failure, failure));
    }

    @Test
    void anExceptionPageBeatsTheStatusPageForTheSameFailure() {
        var resolver = new RegisteredErrorPageResolver(List.of(
            new ErrorPage(HttpStatus.INTERNAL_SERVER_ERROR, "/500"),
            new ErrorPage(IllegalStateException.class, "/ise")));

        var failure = new IllegalStateException();
        assertEquals("/ise", resolver.resolve(500, failure, failure));
    }

    @Test
    void anExceptionWithNoPageOfItsOwnFallsBackToTheStatusPage() {
        var resolver = new RegisteredErrorPageResolver(List.of(
            new ErrorPage(HttpStatus.INTERNAL_SERVER_ERROR, "/500"),
            new ErrorPage(IOException.class, "/io")));

        var failure = new IllegalStateException();
        assertEquals("/500", resolver.resolve(500, failure, failure));
    }

    @Test
    void aPageForTheServletExceptionBeatsOneForItsRootCause() {
        var resolver = new RegisteredErrorPageResolver(List.of(
            new ErrorPage(ServletException.class, "/se"),
            new ErrorPage(IllegalStateException.class, "/ise")));
        var rootCause = new IllegalStateException();

        assertEquals("/se", resolver.resolve(500, new ServletException(rootCause), rootCause));
    }

    @Test
    void noRegisteredPagesResolvesToNothing() {
        var resolver = new RegisteredErrorPageResolver(List.of());

        assertNull(resolver.resolve(404, null, null));
        var failure = new IllegalStateException();
        assertNull(resolver.resolve(500, failure, failure));
    }
}
