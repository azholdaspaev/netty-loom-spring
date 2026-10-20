package io.github.azholdaspaev.nettyloomspring.integration.actuator;

import io.github.azholdaspaev.nettyloomspring.integration.support.BaseIntegrationTest;
import io.github.azholdaspaev.nettyloomspring.integration.support.FormLogin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeapDumpStreamingTest extends BaseIntegrationTest {

    private static final int CLIENTS = 3;

    private static final byte[] HPROF_HEADER = "JAVA PROFILE 1.0.".getBytes(StandardCharsets.US_ASCII);

    @LocalServerPort
    private int port;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void shouldStreamCompleteHeapDumpToConcurrentClients() throws Exception {
        String sessionId = FormLogin.logIn(restTestClient).postLoginSessionId();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/heapdump"))
            .header(HttpHeaders.COOKIE, FormLogin.SESSION_COOKIE + "=" + sessionId)
            .build();
        List<Download> downloads = new ArrayList<>();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
             ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Download>> pending = new ArrayList<>();
            for (int i = 0; i < CLIENTS; i++) {
                pending.add(clients.submit(() -> download(client, request)));
            }
            /*
             * A body cut short of its Content-Length makes HttpClient throw on read, so a truncated
             * or reset stream fails here, through Future.get, before any assertion runs.
             */
            for (Future<Download> download : pending) {
                downloads.add(download.get());
            }
        }

        long complete = downloads.stream().filter(download -> download.status() == 200).count();
        assertTrue(complete >= 1, "at least one client must receive a dump; got " + downloads);
        for (Download download : downloads) {
            if (download.status() == 200) {
                assertEquals(download.contentLength(), download.bytesRead(),
                    "a 200 heap dump must be exactly as long as its Content-Length");
                assertArrayEquals(HPROF_HEADER, download.head(), "a 200 heap dump must be an HPROF file");
            } else {
                assertEquals(429, download.status(), "a dump refused while another is written must be Boot's 429");
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldRedirectToLoginWhenHeapDumpCallerIsAnonymous() {
        restTestClient.get().uri("/actuator/heapdump")
            .exchange()
            .expectStatus().isFound()
            .expectHeader().value(HttpHeaders.LOCATION, location ->
                assertTrue(location.endsWith("/login"), "an anonymous heap dump must redirect to login; got " + location));
    }

    private static Download download(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream body = response.body()) {
            byte[] head = body.readNBytes(HPROF_HEADER.length);
            long rest = body.transferTo(OutputStream.nullOutputStream());
            return new Download(response.statusCode(), contentLength, head.length + rest, head);
        }
    }

    private record Download(int status, long contentLength, long bytesRead, byte[] head) {
    }
}
