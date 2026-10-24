package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Writes one NCSA combined line per exchange that receives a final response (issue #8), on the event
 * loop. Below the pipelining gate, so at most one exchange is open at a time. Every field is confined
 * to the event loop except the trace id, which the dispatch thread hands over through {@link #EXCHANGE}.
 */
public class HttpAccessLogHandler extends ChannelDuplexHandler {

    static final AttributeKey<Exchange> EXCHANGE = AttributeKey.valueOf(HttpAccessLogHandler.class, "exchange");

    private static final String ABSENT = "-";

    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private final BiFunction<HttpRequest, HttpConnectionMetadata, String> remoteAddress;
    private final Logger log;
    private final Clock clock;

    private Ticker ticker;
    private HttpConnectionMetadata connection;
    private Exchange current;

    public HttpAccessLogHandler(BiFunction<HttpRequest, HttpConnectionMetadata, String> remoteAddress) {
        this(remoteAddress, LoggerFactory.getLogger(HttpAccessLogHandler.class), Clock.systemDefaultZone());
    }

    HttpAccessLogHandler(BiFunction<HttpRequest, HttpConnectionMetadata, String> remoteAddress, Logger log,
                         Clock clock) {
        this.remoteAddress = remoteAddress;
        this.log = log;
        this.clock = clock;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ticker = ctx.executor().ticker();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest request) {
            current = log.isInfoEnabled() ? newExchange(ctx, request) : null;
            ctx.channel().attr(EXCHANGE).set(current);
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        Exchange exchange = current;
        if (exchange == null || HttpResponses.isInformational(msg)) {
            ctx.write(msg, promise);
            return;
        }
        if (msg instanceof HttpResponse response) {
            exchange.markResponded(response.status());
        }
        int bytes = msg instanceof HttpContent content && !exchange.bodyless ? content.content().readableBytes() : 0;
        boolean last = msg instanceof LastHttpContent;
        if (bytes == 0 && !last) {
            ctx.write(msg, promise);
            return;
        }
        ChannelPromise tracked = promise.unvoid();
        if (bytes > 0) {
            tracked.addListener(future -> {
                if (future.isSuccess()) {
                    exchange.bytesOut += bytes;
                }
            });
        }
        if (last) {
            current = null;
            ctx.channel().attr(EXCHANGE).compareAndSet(exchange, null);
            // On completion, not invocation: duration_ms runs to the end of the last write (issue #8).
            tracked.addListener(future -> logExchange(exchange));
        }
        ctx.write(msg, tracked);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Exchange exchange = current;
        current = null;
        ctx.channel().attr(EXCHANGE).set(null);
        if (exchange != null && exchange.status != 0) {
            logExchange(exchange);
        }
        ctx.fireChannelInactive();
    }

    private Exchange newExchange(ChannelHandlerContext ctx, HttpRequest request) {
        if (connection == null) {
            connection = HttpConnectionMetadata.from(ctx);
        }
        Exchange exchange = new Exchange(clock.instant(), ticker.nanoTime(), HttpMethod.HEAD.equals(request.method()));
        /*
         * HttpObjectDecoder.invalidMessage marks either a placeholder (GET /bad-request HTTP/1.0) or a head
         * cut short in its header block, so nothing on a failed head describes what the client sent.
         */
        if (request.decoderResult().isFailure()) {
            exchange.remoteIp = replaceEmptyWithDash(connection.remoteAddr());
            return exchange;
        }
        String uri = request.uri();
        int queryStart = uri.indexOf('?');
        HttpHeaders headers = request.headers();
        exchange.remoteIp = replaceEmptyWithDash(remoteAddress.apply(request, connection));
        exchange.method = request.method().name();
        exchange.path = queryStart < 0 ? uri : uri.substring(0, queryStart);
        exchange.query = queryStart < 0 ? ABSENT : replaceEmptyWithDash(uri.substring(queryStart + 1));
        exchange.protocol = request.protocolVersion().text();
        exchange.referer = replaceEmptyWithDash(headers.get(HttpHeaderNames.REFERER));
        exchange.userAgent = replaceEmptyWithDash(headers.get(HttpHeaderNames.USER_AGENT));
        return exchange;
    }

    private void logExchange(Exchange exchange) {
        if (!log.isInfoEnabled()) {
            return;
        }
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(ticker.nanoTime() - exchange.startNanos);
        var line = log.atInfo()
            .addKeyValue("remote_ip", exchange.remoteIp)
            .addKeyValue("method", exchange.method)
            .addKeyValue("path", exchange.path)
            .addKeyValue("query", exchange.query)
            .addKeyValue("protocol", exchange.protocol)
            .addKeyValue("status", exchange.status)
            .addKeyValue("bytes_out", exchange.bytesOut)
            .addKeyValue("duration_ms", durationMillis)
            .addKeyValue("referer", exchange.referer)
            .addKeyValue("user_agent", exchange.userAgent);
        String traceId = exchange.traceId;
        if (traceId != null) {
            line = line.addKeyValue("trace_id", traceId);
        }
        line.log(formatLine(exchange, durationMillis));
    }

    private String formatLine(Exchange exchange, long durationMillis) {
        String target = ABSENT.equals(exchange.query) ? exchange.path : exchange.path + "?" + exchange.query;
        // An IP literal or -, rather than escaped: the field is unquoted, so an escaped space still splits it.
        String remoteIp = NetUtil.isValidIpV4Address(exchange.remoteIp) || NetUtil.isValidIpV6Address(exchange.remoteIp)
            ? exchange.remoteIp : ABSENT;
        return remoteIp
            + " - - [" + TIMESTAMP.format(exchange.arrival.atZone(clock.getZone())) + "] \""
            + exchange.method + " " + escape(target) + " " + exchange.protocol + "\" "
            + exchange.status + " " + (exchange.bytesOut == 0 ? ABSENT : exchange.bytesOut)
            + " \"" + escape(exchange.referer) + "\" \"" + escape(exchange.userAgent) + "\" "
            + durationMillis;
    }

    private static String escape(String value) {
        StringBuilder escaped = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String replacement = switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\r' -> "\\r";
                case '\n' -> "\\n";
                default -> null;
            };
            if (replacement == null) {
                if (escaped != null) {
                    escaped.append(c);
                }
                continue;
            }
            if (escaped == null) {
                escaped = new StringBuilder(value.length() + 8).append(value, 0, i);
            }
            escaped.append(replacement);
        }
        return escaped == null ? value : escaped.toString();
    }

    private static String replaceEmptyWithDash(String value) {
        return value == null || value.isEmpty() ? ABSENT : value;
    }

    static final class Exchange {

        private final Instant arrival;
        private final long startNanos;
        private final boolean headRequest;

        private String remoteIp = ABSENT;
        private String method = ABSENT;
        private String path = ABSENT;
        private String query = ABSENT;
        private String protocol = ABSENT;
        private String referer = ABSENT;
        private String userAgent = ABSENT;

        private int status;
        private boolean bodyless;
        private long bytesOut;

        /** Written by the dispatch thread, read by the event loop once the last write completes. */
        private volatile String traceId;

        private Exchange(Instant arrival, long startNanos, boolean headRequest) {
            this.arrival = arrival;
            this.startNanos = startNanos;
            this.headRequest = headRequest;
        }

        void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        /** HttpServerCodec drops the body of these, so none of it is counted ({@code isContentAlwaysEmpty}). */
        private void markResponded(HttpResponseStatus responseStatus) {
            status = responseStatus.code();
            bodyless = headRequest || HttpResponses.carriesNoBody(responseStatus);
        }
    }
}
