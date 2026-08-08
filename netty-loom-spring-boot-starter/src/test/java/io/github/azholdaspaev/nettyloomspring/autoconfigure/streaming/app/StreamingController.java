package io.github.azholdaspaev.nettyloomspring.autoconfigure.streaming.app;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Endpoints written against {@code HttpServletResponse} directly, which is how a body streams without
 * servlet async: {@code SseEmitter} and {@code StreamingResponseBody} both go through
 * {@code startAsync()}, still stubbed out under issue #18.
 */
@RestController
public class StreamingController {

    /** Far past any single buffer in the response path, so the body can only arrive as many chunks. */
    public static final int LARGE_BODY_BYTES = 2 * 1024 * 1024;

    private static final int BLOCK_BYTES = 8192;
    public static final String SIZED_BODY = "a body whose length the handler knows up front";

    private final StreamingGate gate;

    StreamingController(StreamingGate gate) {
        this.gate = gate;
    }

    /** Emits three events, each held back until the test asks for it. */
    @GetMapping(value = "/streaming/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void events(HttpServletResponse response) throws IOException, InterruptedException {
        ServletOutputStream out = response.getOutputStream();
        for (int event = 1; event <= 3; event++) {
            gate.awaitRelease();
            out.write(("data: event " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
            response.flushBuffer();
        }
    }

    @GetMapping("/streaming/large")
    public void large(HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        ServletOutputStream out = response.getOutputStream();
        byte[] block = new byte[BLOCK_BYTES];
        Arrays.fill(block, (byte) 'x');
        for (int written = 0; written < LARGE_BODY_BYTES; written += BLOCK_BYTES) {
            out.write(block);
        }
    }

    /** The shape {@code /actuator/heapdump} has: a length is known, so the body streams unframed. */
    @GetMapping("/streaming/sized")
    public void sized(HttpServletResponse response) throws IOException {
        byte[] body = SIZED_BODY.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /**
     * A JSON body, which is the case that matters: unlike {@code StringHttpMessageConverter}, Jackson
     * cannot report a content length without serializing first, so nothing declares one.
     */
    @GetMapping("/streaming/entity")
    public ResponseEntity<Message> entity() {
        return ResponseEntity.ok(new Message("returned as an entity"));
    }

    /**
     * An explicit 304 carrying a body. Spring's own not-modified shortcut is gated on status 200, so
     * this reaches the converter, and a 304 can never carry a body on the wire.
     */
    @GetMapping("/streaming/not-modified")
    public ResponseEntity<Message> notModified() {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).body(new Message("never sent"));
    }

    public record Message(String text) {
    }
}
