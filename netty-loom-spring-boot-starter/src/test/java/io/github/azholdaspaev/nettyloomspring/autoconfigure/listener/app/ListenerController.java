package io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class ListenerController {

    public static final String NONE = "none";

    @GetMapping("/listener/ping")
    @ResponseBody
    public String ping() {
        return "ok";
    }

    @GetMapping("/listener/session/create")
    @ResponseBody
    public String createSession(HttpServletRequest request) {
        return request.getSession().getId();
    }

    @GetMapping("/listener/session/attributes")
    @ResponseBody
    public String cycleAttributes(HttpServletRequest request) {
        HttpSession session = request.getSession();
        session.setAttribute("value", "first");
        session.setAttribute("value", "second");
        session.removeAttribute("value");
        return session.getId();
    }

    @GetMapping("/listener/session/invalidate")
    @ResponseBody
    public String invalidateSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return NONE;
        }
        session.invalidate();
        return "invalidated";
    }

    @GetMapping("/listener/session/rotate")
    @ResponseBody
    public String rotateSessionId(HttpServletRequest request) {
        if (request.getSession(false) == null) {
            return NONE;
        }
        return request.changeSessionId();
    }

    @GetMapping("/listener/request/attributes")
    @ResponseBody
    public String cycleRequestAttributes(HttpServletRequest request) {
        request.setAttribute("stage", "first");
        request.setAttribute("stage", "second");
        request.removeAttribute("stage");
        return "ok";
    }
}
