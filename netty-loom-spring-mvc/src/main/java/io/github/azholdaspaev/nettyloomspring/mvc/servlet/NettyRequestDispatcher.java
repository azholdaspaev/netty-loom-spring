package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

class NettyRequestDispatcher implements RequestDispatcher {

    private final NettyDispatchFactory factory;
    private final String targetPath;
    private final String queryString;

    NettyRequestDispatcher(NettyDispatchFactory factory, String targetPath, String queryString) {
        this.factory = factory;
        this.targetPath = targetPath;
        this.queryString = queryString;
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        // isCommitted() before resetBuffer(), not instead of it: sendError and sendRedirect commit
        // without writing the head, which resetBuffer's own guard would let through.
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot forward after the response has been committed");
        }
        response.resetBuffer();
        dispatch((HttpServletRequest) request, response, DispatcherType.FORWARD);
    }

    void dispatch(HttpServletRequest request, ServletResponse response, DispatcherType dispatcherType)
        throws ServletException, IOException {
        var dispatched = new NettyDispatchRequestWrapper(factory, request, targetPath, queryString, dispatcherType);
        factory.chainFor(dispatched).doFilter(dispatched, response);
    }

    @Override
    public void include(ServletRequest request, ServletResponse response) {
        throw new UnsupportedOperationException("RequestDispatcher.include is not supported");
    }
}
