package io.github.azholdaspaev.nettyloomspring.autoconfigure.forwardheaders.app;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@SpringBootApplication
public class ForwardHeadersTestApplication {

    @Bean
    public ServletListenerRegistrationBean<ServletRequestListener> requestListener() {
        return new ServletListenerRegistrationBean<>(new ServletRequestListener() {
            @Override
            public void requestInitialized(ServletRequestEvent event) {
                event.getServletRequest().setAttribute("listenerScheme", event.getServletRequest().getScheme());
            }
        });
    }

    @RestController
    public static class ForwardHeadersController {

        @GetMapping("/origin")
        public String getOrigin(HttpServletRequest request) {
            return String.join("|", request.getScheme(), request.getServerName(),
                Integer.toString(request.getServerPort()), request.getRemoteAddr(),
                Integer.toString(request.getRemotePort()), Boolean.toString(request.isSecure()),
                Boolean.toString(request.getServletConnection().isSecure()), request.getRequestURL().toString(),
                String.valueOf(request.getHeader("Forwarded")), String.valueOf(request.getAttribute("listenerScheme")));
        }

        @GetMapping("/session")
        public String newSession(HttpServletRequest request) {
            request.getSession();
            request.changeSessionId();
            return "session";
        }

        @GetMapping("/redirect")
        public void redirect(HttpServletResponse response) throws IOException {
            response.sendRedirect("/target");
        }
    }
}
