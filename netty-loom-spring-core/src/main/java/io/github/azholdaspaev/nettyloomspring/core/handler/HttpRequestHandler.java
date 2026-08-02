package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    private final HttpRequestDispatcher requestDispatcher;
    private final Executor dispatchExecutor;
    private final HttpConnectionRegistry connectionRegistry;

    /**
     * @param dispatchExecutor must run every accepted task exactly once. One that silently discards
     *                         a task it accepted — {@code ThreadPoolExecutor} with
     *                         {@code DiscardPolicy}, say — leaks the request and leaves the dispatch
     *                         counted, which holds every later graceful shutdown open for its whole
     *                         grace period. Rejecting by throwing is fine; that path is handled.
     */
    public HttpRequestHandler(HttpRequestDispatcher requestDispatcher,
                              Executor dispatchExecutor,
                              HttpConnectionRegistry connectionRegistry) {
        this.requestDispatcher = requestDispatcher;
        this.dispatchExecutor = dispatchExecutor;
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        request.retain();
        HttpConnectionMetadata connection = HttpConnectionMetadata.from(ctx);
        dispatch(ctx, request, connection);
    }

    private void dispatch(ChannelHandlerContext ctx, FullHttpRequest request, HttpConnectionMetadata connection) {
        connectionRegistry.dispatchStarted();
        try {
            dispatchExecutor.execute(() -> {
                try {
                    FullHttpResponse response = requestDispatcher.handle(request, connection);
                    echoHttp10KeepAlive(request, response);
                    ctx.writeAndFlush(response);
                } catch (Throwable cause) {
                    reportDispatchFailure(ctx, request, cause);
                } finally {
                    // Ahead of release(), which throws if the dispatcher released the request it was
                    // handed: the count is global and reset() does not clear it, so it must not
                    // depend on a call that can fail.
                    connectionRegistry.dispatchFinished();
                    try {
                        request.release();
                    } catch (Throwable ignored) {
                        // Nothing may escape the task, fatal errors included. The catch below
                        // compensates for a submission that never ran; on an Executor that runs tasks
                        // inline it would otherwise also see one that did, and count the same dispatch
                        // out twice. That is why this is the one wide catch here that does not pair
                        // with a rethrowIfFatal, as NettyListenerRegistry's do: rethrowing would put
                        // the throwable back on the path to that catch and restore the double count.
                    }
                }
            });
        } catch (Throwable cause) {
            connectionRegistry.dispatchFinished();
            request.release();
            ctx.fireExceptionCaught(cause);
        }
    }

    /**
     * Makes the hop onto the event loop that {@code ctx.fireExceptionCaught} would make anyway, so a
     * loop that terminated under a dispatch rejects it here rather than inside Netty — which answers
     * its own rejection with two stack traces, neither naming the request (issue #109). Same shape as
     * {@link HttpPipeliningHandler}'s deferred re-open, for the same reason. Only the dispatch task
     * needs it: the outer {@code catch} already runs on the loop, so it has nothing to reject.
     */
    private static void reportDispatchFailure(ChannelHandlerContext ctx, FullHttpRequest request, Throwable cause) {
        try {
            ctx.executor().execute(() -> ctx.fireExceptionCaught(cause));
        } catch (RejectedExecutionException terminated) {
            String dispatch = request.method() + " " + request.uri();
            log.warn("Abandoned {} after the event loop terminated: {}", dispatch, cause.toString());
            log.debug("Abandoned {}", dispatch, cause);
        }
    }

    /**
     * An HTTP/1.0 client closes the connection unless the server spells out {@code Connection: keep-alive}.
     * {@link io.netty.handler.codec.http.HttpServerKeepAliveHandler} only ever writes {@code Connection: close},
     * so the affirmative header has to be added here — but never over a close the dispatcher asked for.
     * The response itself is always HTTP/1.1, hence the explicit version passed to
     * {@link HttpUtil#setKeepAlive(io.netty.handler.codec.http.HttpHeaders, HttpVersion, boolean)}.
     */
    private static void echoHttp10KeepAlive(FullHttpRequest request, FullHttpResponse response) {
        if (HttpVersion.HTTP_1_0.equals(request.protocolVersion())
            && HttpUtil.isKeepAlive(request)
            && HttpUtil.isKeepAlive(response)) {
            HttpUtil.setKeepAlive(response.headers(), HttpVersion.HTTP_1_0, true);
        }
    }
}
