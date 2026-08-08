package io.github.azholdaspaev.nettyloomspring.mvc.handler;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpResponseWriter;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyFilterChain;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyHttpServletRequest;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyHttpServletResponse;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.RegisteredFilter;
import io.netty.handler.codec.http.FullHttpRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.List;

public class SpringHttpRequestDispatcher implements HttpRequestDispatcher {

    private final NettyServletContext servletContext;
    private final FilterChain terminal;

    public SpringHttpRequestDispatcher(DispatcherServlet dispatcherServlet, NettyServletContext servletContext) {
        this.servletContext = servletContext;
        // Bound once here rather than re-creating the method reference per request.
        this.terminal = dispatcherServlet::service;
    }

    @Override
    public void handle(FullHttpRequest request, HttpConnectionMetadata connection, HttpResponseWriter writer)
        throws Exception {
        // The response is built first because the request holds on to it: a session created mid-dispatch
        // has to write its Set-Cookie straight away, since addCookie is ignored once the response is
        // committed by sendRedirect or sendError.
        NettyHttpServletResponse servletResponse =
            new NettyHttpServletResponse(servletContext.getCookieSameSiteResolver(), writer);
        NettyHttpServletRequest servletRequest =
            new NettyHttpServletRequest(request, connection, servletContext, servletResponse);

        // Out-of-context request: a plain 404 before running filters or the servlet, because Boot's
        // PathPatternParser would otherwise throw on a URI outside the context path.
        if (!servletRequest.isWithinContext()) {
            servletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
            servletResponse.complete();
            return;
        }

        // Filter URL patterns are context-relative, so match on the in-context servlet path.
        String servletPath = servletRequest.getServletPath();
        List<RegisteredFilter> applicable = servletContext.getRegisteredFilters().stream()
            .filter(filter -> filter.matches(servletPath, servletRequest.getDispatcherType()))
            .toList();

        // Bracketing the chain, not the whole method: the out-of-context 404 above never enters this
        // context, so announcing it would report a request the application never saw. requestDestroyed is
        // in a finally because it is a release, not a notification -- RequestContextListener.requestDestroyed
        // runs the destruction callbacks of every @RequestScope bean the dispatch created, and unlike a
        // stranded ThreadLocal that is not swept up by the request's virtual thread ending. The init call
        // stays outside the try, so a request whose setup failed gets no requestDestroyed it was never
        // owed; fireRequestInitialized releases the prefix it notified before propagating.
        servletContext.getListenerRegistry().fireRequestInitialized(servletRequest);
        try {
            NettyFilterChain chain = new NettyFilterChain(applicable, terminal);
            chain.doFilter(servletRequest, servletResponse);
        } finally {
            servletContext.getListenerRegistry().fireRequestDestroyed(servletRequest);
        }

        // Outside the try: a dispatch that threw has an incoherent response to send, and finishing it
        // here would answer the request with it instead of letting the failure reach the connection.
        servletResponse.complete();
    }
}
