package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.util.StringUtils;

import java.io.IOException;

class NettyRequestDispatcher implements RequestDispatcher {

    private final NettyServletContext context;
    private final String targetPath;
    private final String queryString;

    NettyRequestDispatcher(NettyServletContext context, String targetPath, String queryString) {
        this.context = context;
        this.targetPath = targetPath;
        this.queryString = queryString;
    }

    static RequestDispatcher forContextPath(NettyServletContext context, String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        return resolve(context, path);
    }

    static RequestDispatcher forRequestPath(NettyServletContext context, String servletPath, String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("/")) {
            return resolve(context, path);
        }
        // A request for the bare context path has an empty servlet path, whose directory is the root.
        String directory = servletPath.isEmpty() ? "/" : servletPath;
        return resolve(context, StringUtils.applyRelativePath(directory, path));
    }

    // Context-relative throughout, so a path canonicalising out of the context yields no dispatcher at
    // all; the query splits off first so a ".." inside it is never normalised (Servlet 6.0, 3.5.2).
    private static RequestDispatcher resolve(NettyServletContext context, String path) {
        int queryStart = path.indexOf('?');
        String targetPath = queryStart < 0 ? path : path.substring(0, queryStart);
        String queryString = queryStart < 0 ? null : path.substring(queryStart + 1);
        String normalized = StringUtils.cleanPath(targetPath);
        if (!normalized.startsWith("/") || normalized.equals("/..") || normalized.startsWith("/../")) {
            return null;
        }
        return new NettyRequestDispatcher(context, normalized, queryString);
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        // isCommitted() before resetBuffer(), not instead of it: sendError and sendRedirect commit
        // without writing the head, which resetBuffer's own guard would let through.
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot forward after the response has been committed");
        }
        response.resetBuffer();

        var forwarded = new NettyForwardRequest(context, (HttpServletRequest) request, targetPath, queryString);
        NettyFilterChain.forDispatch(context, forwarded).doFilter(forwarded, response);
    }

    @Override
    public void include(ServletRequest request, ServletResponse response) {
        throw new UnsupportedOperationException("RequestDispatcher.include is not supported");
    }
}
