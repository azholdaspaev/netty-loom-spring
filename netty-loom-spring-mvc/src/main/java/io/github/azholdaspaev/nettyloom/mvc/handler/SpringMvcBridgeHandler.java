package io.github.azholdaspaev.nettyloom.mvc.handler;

import io.github.azholdaspaev.nettyloom.mvc.filter.FilterChainAdapter;
import io.github.azholdaspaev.nettyloom.mvc.servlet.NettyHttpServletRequest;
import io.github.azholdaspaev.nettyloom.mvc.servlet.NettyHttpServletResponse;
import io.github.azholdaspaev.nettyloom.mvc.servlet.NettyServletContext;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.ExecutorService;

/**
 * Netty channel handler that bridges HTTP requests to Spring MVC via servlet adapters.
 *
 * <p>This handler:
 * <ul>
 *   <li>Receives Netty HTTP requests on the event loop thread</li>
 *   <li>Dispatches processing to virtual threads</li>
 *   <li>Creates servlet request/response adapters</li>
 *   <li>Executes the filter chain terminating at DispatcherServlet</li>
 *   <li>Converts servlet response back to Netty response</li>
 * </ul>
 *
 * <p>This handler is marked @Sharable because it's stateless - all request-specific
 * state is held in the servlet adapters created per request.
 */
@ChannelHandler.Sharable
public class SpringMvcBridgeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(SpringMvcBridgeHandler.class);

    private final NettyServletContext servletContext;
    private final ExecutorService executorService;
    private final String contextPath;

    /**
     * Creates a bridge handler for Spring MVC integration.
     *
     * @param servletContext the servlet context containing registered servlets and filters
     * @param executorService the executor for virtual thread dispatch
     * @param contextPath the context path (e.g., "" or "/api")
     */
    public SpringMvcBridgeHandler(NettyServletContext servletContext,
                                  ExecutorService executorService,
                                  String contextPath) {
        this.servletContext = servletContext;
        this.executorService = executorService;
        this.contextPath = contextPath != null ? contextPath : "";
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        // CRITICAL: Retain request for async processing on virtual thread
        request.retain();

        executorService.submit(() -> {
            try {
                handleRequest(ctx, request);
            } catch (Exception e) {
                logger.error("Error processing request: {} {}", request.method(), request.uri(), e);
                sendErrorResponse(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            } finally {
                // CRITICAL: Release request after processing
                request.release();
            }
        });
    }

    private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        // Validate request
        if (!request.decoderResult().isSuccess()) {
            sendErrorResponse(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid HTTP request");
            return;
        }

        try {
            // Create servlet adapters
            NettyHttpServletRequest servletRequest = new NettyHttpServletRequest(
                    request, ctx, servletContext, contextPath);
            NettyHttpServletResponse servletResponse = new NettyHttpServletResponse();

            // Get DispatcherServlet (registered by Spring Boot)
            Servlet dispatcherServlet = servletContext.getServlet("dispatcherServlet");
            if (dispatcherServlet == null) {
                logger.error("DispatcherServlet not found in servlet context");
                sendErrorResponse(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                        "DispatcherServlet not configured");
                return;
            }

            // Get matching filters for this request path
            String requestPath = servletRequest.getRequestURI();
            Collection<Filter> filters = servletContext.getMatchingFilters(requestPath);

            // Create and execute filter chain
            FilterChainAdapter filterChain = new FilterChainAdapter(
                    filters.toArray(new Filter[0]),
                    dispatcherServlet
            );
            filterChain.doFilter(servletRequest, servletResponse);

            // Convert servlet response to Netty response
            FullHttpResponse nettyResponse = servletResponse.toNettyResponse();

            // Handle keep-alive
            handleKeepAlive(request, nettyResponse, ctx);

        } catch (Exception e) {
            logger.error("Exception during request processing", e);
            sendErrorResponse(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal server error");
        }
    }

    /**
     * Handles HTTP keep-alive based on request and sends response.
     */
    private void handleKeepAlive(FullHttpRequest request, FullHttpResponse response,
                                  ChannelHandlerContext ctx) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);

        if (keepAlive) {
            // Add keep-alive header if not present
            if (!response.headers().contains(HttpHeaderNames.CONNECTION)) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            ctx.writeAndFlush(response);
        } else {
            // Close connection after sending response
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Sends an HTTP error response.
     */
    private void sendErrorResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                                    HttpResponseStatus status, String message) {
        byte[] content = message.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(content)
        );

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive && status.code() < 500) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Uncaught exception in channel handler", cause);
        ctx.close();
    }
}
