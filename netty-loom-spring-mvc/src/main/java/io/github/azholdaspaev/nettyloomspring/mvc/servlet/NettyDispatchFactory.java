package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds what a dispatch runs through: the {@link RequestDispatcher} a path resolves to, and the
 * {@link FilterChain} one dispatch traverses. It holds the terminal the chain ends in, and reaches the
 * registered filters and the context path through the context.
 */
public class NettyDispatchFactory {

    private final NettyServletContext context;
    private final FilterChain terminal;

    public NettyDispatchFactory(NettyServletContext context, FilterChain terminal) {
        this.context = context;
        this.terminal = terminal;
    }

    public RequestDispatcher forContextPath(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        return resolve(path);
    }

    public RequestDispatcher forRequestPath(String servletPath, String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("/")) {
            return resolve(path);
        }
        // A request for the bare context path has an empty servlet path, whose directory is the root.
        String directory = servletPath.isEmpty() ? "/" : servletPath;
        return resolve(StringUtils.applyRelativePath(directory, path));
    }

    /** Filter URL patterns are context-relative, so registrations match on the servlet path. */
    public FilterChain chainFor(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        DispatcherType dispatcherType = request.getDispatcherType();
        List<RegisteredFilter> applicable = context.getRegisteredFilters().stream()
            .filter(filter -> filter.matches(servletPath, dispatcherType))
            .toList();
        return new NettyFilterChain(applicable, terminal);
    }

    // Context-relative throughout, so a path canonicalising out of the context yields no dispatcher at
    // all; the query splits off first so a ".." inside it is never normalised (Servlet 6.0, 3.5.2).
    private RequestDispatcher resolve(String path) {
        int queryStart = path.indexOf('?');
        String targetPath = queryStart < 0 ? path : path.substring(0, queryStart);
        String queryString = queryStart < 0 ? null : path.substring(queryStart + 1);
        String normalized = StringUtils.cleanPath(targetPath);
        if (escapesContext(normalized)) {
            return null;
        }
        return new NettyRequestDispatcher(this, normalized, queryString);
    }

    // Decided on the form the consumer sees rather than on the dispatched path, because Spring's
    // DefaultPathContainer strips ';' parameters and percent-decodes each segment: a guard reading
    // the raw path would call "..;" and "%2e%2e" ordinary segments where Spring calls them "..".
    // The dispatched path stays undecoded, since getRequestURI() must report the URI as sent.
    private static boolean escapesContext(String normalized) {
        String canonical;
        try {
            canonical = StringUtils.cleanPath(StringUtils.uriDecode(
                UrlPathHelper.defaultInstance.removeSemicolonContent(normalized), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException undecodable) {
            return true;
        }
        return !canonical.startsWith("/") || canonical.equals("/..") || canonical.startsWith("/../");
    }
}
