package io.github.azholdaspaev.nettyloomspring.mvc.handler;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpConnectionMetadata;
import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyFilterChain;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyHttpServletRequest;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyHttpServletResponse;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyServletContext;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.RegisteredFilter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import jakarta.servlet.FilterChain;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.List;

public class SpringHttpRequestDispatcher implements HttpRequestDispatcher {

    private final NettyServletContext servletContext;
    private final FilterChain terminal;

    public SpringHttpRequestDispatcher(DispatcherServlet dispatcherServlet, NettyServletContext servletContext) {
        this.servletContext = servletContext;
        // The chain terminal hands the request to the DispatcherServlet; bound once here rather
        // than re-creating the method reference per request.
        this.terminal = dispatcherServlet::service;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, HttpConnectionMetadata connection) throws Exception {
        NettyHttpServletRequest servletRequest = new NettyHttpServletRequest(request, connection, servletContext);
        NettyHttpServletResponse servletResponse = new NettyHttpServletResponse();

        String requestPath = servletRequest.getRequestURI();
        List<RegisteredFilter> applicable = servletContext.getRegisteredFilters().stream()
            .filter(filter -> filter.matches(requestPath, servletRequest.getDispatcherType()))
            .toList();

        NettyFilterChain chain = new NettyFilterChain(applicable, terminal);
        chain.doFilter(servletRequest, servletResponse);

        return servletResponse.toFullHttpResponse();
    }
}
