package io.github.azholdaspaev.nettyloomspring.autoconfigure.requestbody.app;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

@RestController
public class RequestBodyController {

    /** Small enough that one read cannot span two chunks, which is what makes the hand-off meaningful. */
    private static final int READ_BUFFER_BYTES = 64;

    private final RequestBodyGate gate;

    RequestBodyController(RequestBodyGate gate) {
        this.gate = gate;
    }

    @PostMapping("/upload/gated")
    public String gated(HttpServletRequest request) throws IOException, InterruptedException {
        InputStream body = request.getInputStream();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int total = 0;
        for (int read = body.read(buffer); read != -1; read = body.read(buffer)) {
            total += read;
            gate.reportRead(read);
        }
        return "read " + total;
    }

    @PostMapping("/upload/count")
    public String count(HttpServletRequest request) throws IOException {
        return "read " + request.getInputStream().readAllBytes().length;
    }

    @PostMapping("/upload/ignored")
    public String ignored() {
        return "answered without reading";
    }
}
