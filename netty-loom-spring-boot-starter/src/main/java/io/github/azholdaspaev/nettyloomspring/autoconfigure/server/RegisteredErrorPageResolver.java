package io.github.azholdaspaev.nettyloomspring.autoconfigure.server;

import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyErrorPageResolver;
import org.springframework.boot.web.error.ErrorPage;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The application's {@link ErrorPage} registrations, folded into the single lookup the servlet bridge
 * reads. The precedence is Tomcat's -- exception before status, both before the global page -- so that
 * the same registrations behave the same way here as they would on Tomcat.
 */
class RegisteredErrorPageResolver implements NettyErrorPageResolver {

    private final Map<Integer, String> byStatus = new HashMap<>();
    private final Map<String, String> byExceptionName = new HashMap<>();
    private String global;

    RegisteredErrorPageResolver(Collection<ErrorPage> pages) {
        for (ErrorPage page : pages) {
            if (page.getExceptionName() != null) {
                byExceptionName.put(page.getExceptionName(), page.getPath());
            } else if (page.getStatus() != null) {
                byStatus.put(page.getStatusCode(), page.getPath());
            } else {
                global = page.getPath();
            }
        }
    }

    @Override
    public String resolve(int status, Throwable failure, Throwable rootCause) {
        String path = byException(failure);
        if (path == null) {
            path = byException(rootCause);
        }
        return path != null ? path : byStatus.getOrDefault(status, global);
    }

    private String byException(Throwable failure) {
        for (Class<?> type = failure == null ? null : failure.getClass();
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            String path = byExceptionName.get(type.getName());
            if (path != null) {
                return path;
            }
        }
        return null;
    }
}
