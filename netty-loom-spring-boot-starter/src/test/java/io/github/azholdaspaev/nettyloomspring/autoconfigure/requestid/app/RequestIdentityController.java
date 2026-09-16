package io.github.azholdaspaev.nettyloomspring.autoconfigure.requestid.app;

import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class RequestIdentityController {

    public static final String DISPATCHED = "/identity/dispatched";

    private static final String RECORDED = "recorded";

    @GetMapping("/identity")
    String identity(HttpServletRequest request) {
        return describe(request);
    }

    @GetMapping("/identity/forward")
    void forward(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        request.setAttribute(RECORDED, describe(request));
        request.getRequestDispatcher(DISPATCHED).forward(request, response);
    }

    @GetMapping("/identity/fail")
    void fail(HttpServletRequest request) {
        request.setAttribute(RECORDED, describe(request));
        throw new IllegalStateException("recorded, then failed");
    }

    @GetMapping(DISPATCHED)
    String dispatched(HttpServletRequest request) {
        return request.getAttribute(RECORDED) + "\n" + describe(request);
    }

    private static String describe(HttpServletRequest request) {
        ServletConnection connection = request.getServletConnection();
        return String.join("|",
            request.getRequestId(),
            request.getProtocolRequestId(),
            request.getDispatcherType().name(),
            connection.getConnectionId(),
            connection.getProtocol(),
            connection.getProtocolConnectionId(),
            String.valueOf(connection.isSecure()));
    }
}
