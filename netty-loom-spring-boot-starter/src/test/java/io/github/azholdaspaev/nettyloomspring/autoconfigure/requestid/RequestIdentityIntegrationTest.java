package io.github.azholdaspaev.nettyloomspring.autoconfigure.requestid;

import io.github.azholdaspaev.nettyloomspring.autoconfigure.requestid.app.RequestIdentityTestApplication;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpClient;
import io.github.azholdaspaev.nettyloomspring.autoconfigure.support.RawHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Which socket a request arrived on is what an HTTP client library hides, and the connection id is
 * exactly that, so these tests speak HTTP over a raw socket.
 */
@SpringBootTest(
    classes = RequestIdentityTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RequestIdentityIntegrationTest {

    @LocalServerPort
    int port;

    private record Identity(String requestId, String protocolRequestId, String dispatcherType,
                            String connectionId, String protocol, String protocolConnectionId,
                            boolean secure) {

        static Identity parse(String line) {
            String[] fields = line.split("\\|", -1);
            assertEquals(7, fields.length, "the controller reports seven fields; got " + line);
            return new Identity(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5],
                Boolean.parseBoolean(fields[6]));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldGiveEachRequestOnOneSocketItsOwnIdAndOneConnectionId() throws Exception {
        try (Socket socket = connect()) {
            Identity first = Identity.parse(exchange(socket, "/identity").readBody());
            Identity second = Identity.parse(exchange(socket, "/identity").readBody());

            assertFalse(first.requestId().isEmpty(), "getRequestId() must not be the empty string");
            assertNotEquals(first.requestId(), second.requestId(),
                "two requests on one keep-alive socket must not share a request id");
            assertFalse(first.connectionId().isEmpty(), "getConnectionId() must not be the empty string");
            assertEquals(first.connectionId(), second.connectionId(),
                "two requests on one keep-alive socket must report the same connection id");
            assertEquals("", first.protocolRequestId(), "HTTP/1.x defines no protocol request id");
            assertEquals("REQUEST", first.dispatcherType());
            assertEquals("http/1.1", first.protocol(), "the ALPN identification sequence, as the spec requires");
            assertEquals("", first.protocolConnectionId(), "HTTP/1.x defines no protocol connection id");
            assertFalse(first.secure(), "a plain-text socket must not report a secure connection");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldGiveDifferentConnectionIdsToTwoSockets() throws Exception {
        try (Socket first = connect(); Socket second = connect()) {
            Identity onFirst = Identity.parse(exchange(first, "/identity").readBody());
            Identity onSecond = Identity.parse(exchange(second, "/identity").readBody());

            assertNotEquals(onFirst.connectionId(), onSecond.connectionId(),
                "requests on two sockets must report different connection ids");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldKeepOriginalRequestIdentityAcrossForward() throws Exception {
        try (Socket socket = connect()) {
            RawHttpResponse response = exchange(socket, "/identity/forward");

            assertEquals(200, response.status());
            assertDispatchKeptIdentity(response.readBody(), "FORWARD");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldKeepOriginalRequestIdentityOnErrorPageDispatch() throws Exception {
        try (Socket socket = connect()) {
            RawHttpResponse response = exchange(socket, "/identity/fail");

            assertEquals(500, response.status());
            assertDispatchKeptIdentity(response.readBody(), "ERROR");
        }
    }

    private static void assertDispatchKeptIdentity(String body, String dispatcherType) {
        String[] lines = body.split("\n");
        assertEquals(2, lines.length, "the dispatched endpoint reports the recorded line and its own; got " + body);
        Identity before = Identity.parse(lines[0]);
        Identity after = Identity.parse(lines[1]);

        assertEquals("REQUEST", before.dispatcherType());
        assertEquals(dispatcherType, after.dispatcherType());
        assertFalse(before.requestId().isEmpty(), "getRequestId() must not be the empty string");
        assertEquals(before.requestId(), after.requestId(),
            "the dispatched request must report the original request's id");
        assertFalse(before.connectionId().isEmpty(), "getConnectionId() must not be the empty string");
        assertEquals(before.connectionId(), after.connectionId(),
            "the dispatched request must report the original request's connection");
        assertEquals(before.secure(), after.secure(),
            "the dispatched request must report the original request's connection");
    }

    private Socket connect() throws IOException {
        return RawHttpClient.connect(port, Duration.ofSeconds(5));
    }

    private static RawHttpResponse exchange(Socket socket, String path) throws IOException {
        RawHttpClient.send(socket, "GET " + path + " HTTP/1.1", "Host: localhost");
        return RawHttpResponse.read(socket.getInputStream());
    }
}
