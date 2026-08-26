package io.github.azholdaspaev.nettyloomspring.autoconfigure.forward.app;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Controller
public class ForwardController {

    @GetMapping("/forward/source")
    public String source() {
        return "forward:/forward/target";
    }

    @GetMapping("/forward/manual")
    public void manual(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        request.getRequestDispatcher("/forward/target?who=target").forward(request, response);
    }

    @GetMapping("/forward/dirty")
    public void dirty(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        response.getOutputStream().write("discarded".getBytes(StandardCharsets.UTF_8));
        request.getRequestDispatcher("/forward/target").forward(request, response);
    }

    @GetMapping("/forward/committed")
    public void committed(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.getWriter().write("committed");
        response.flushBuffer();
        try {
            request.getRequestDispatcher("/forward/target").forward(request, response);
        } catch (IllegalStateException | ServletException e) {
            response.getWriter().write("-rejected");
        }
    }

    @GetMapping("/forward/target")
    @ResponseBody
    public String target(HttpServletRequest request) {
        return "target"
            + " uri=" + request.getRequestURI()
            + " who=" + request.getParameter("who")
            + " from=" + request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI)
            + " trace=" + request.getAttribute(ForwardTestFixtures.TRACE);
    }
}
