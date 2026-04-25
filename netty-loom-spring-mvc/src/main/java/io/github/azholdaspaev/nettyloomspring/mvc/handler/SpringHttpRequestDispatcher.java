package io.github.azholdaspaev.nettyloomspring.mvc.handler;

import io.github.azholdaspaev.nettyloomspring.core.handler.HttpRequestDispatcher;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyHttpServletRequest;
import io.github.azholdaspaev.nettyloomspring.mvc.servlet.NettyHttpServletResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import org.springframework.web.servlet.DispatcherServlet;

public class SpringHttpRequestDispatcher implements HttpRequestDispatcher {

    private final DispatcherServlet dispatcherServlet;

    public SpringHttpRequestDispatcher(DispatcherServlet dispatcherServlet) {
        this.dispatcherServlet = dispatcherServlet;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request) throws Exception {
        NettyHttpServletRequest servletRequest = new NettyHttpServletRequest(request);
        NettyHttpServletResponse servletResponse = new NettyHttpServletResponse();

        dispatcherServlet.service(servletRequest, servletResponse);

        return servletResponse.toFullHttpResponse();
    }
}
