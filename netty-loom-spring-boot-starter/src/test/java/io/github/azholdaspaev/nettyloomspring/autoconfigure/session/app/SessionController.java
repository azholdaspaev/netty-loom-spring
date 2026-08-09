package io.github.azholdaspaev.nettyloomspring.autoconfigure.session.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
public class SessionController {

    public static final String NONE = "none";

    @GetMapping("/session/set")
    @ResponseBody
    public String set(HttpServletRequest request, @RequestParam String value) {
        HttpSession session = request.getSession();
        session.setAttribute("value", value);
        return session.getId();
    }

    @GetMapping("/session/get")
    @ResponseBody
    public String get(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return NONE;
        }
        Object value = session.getAttribute("value");
        return value == null ? NONE : value.toString();
    }

    @GetMapping("/session/id")
    @ResponseBody
    public String id(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? NONE : session.getId();
    }

    @GetMapping("/session/stateless")
    @ResponseBody
    public String stateless() {
        return "ok";
    }

    @GetMapping("/session/invalidate")
    @ResponseBody
    public String invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return NONE;
        }
        session.invalidate();
        return "invalidated";
    }

    /**
     * Mirrors what Spring Security does on login: rotate the id, keeping the session's contents.
     */
    @GetMapping("/session/rotate")
    @ResponseBody
    public String rotate(HttpServletRequest request) {
        request.getSession();
        return request.changeSessionId();
    }

    @GetMapping("/session/isnew")
    @ResponseBody
    public String isNew(HttpServletRequest request) {
        return Boolean.toString(request.getSession().isNew());
    }

    @GetMapping("/session/requested-id")
    @ResponseBody
    public String requestedId(HttpServletRequest request) {
        return request.getRequestedSessionId() + ":" + request.isRequestedSessionIdValid();
    }

    /**
     * The regression case for cookie emission: {@code RedirectView} saves the flash map -- which creates
     * the session -- and only then calls {@code sendRedirect}, which commits the response. Emitting the
     * session cookie any later than creation time would silently drop it here.
     */
    @GetMapping("/session/flash")
    public String flash(RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("flashed", "hello");
        return "redirect:/session/flash-target";
    }

    @GetMapping("/session/flash-target")
    @ResponseBody
    public String flashTarget(Map<String, Object> model) {
        Object flashed = model.get("flashed");
        return flashed == null ? NONE : flashed.toString();
    }
}
