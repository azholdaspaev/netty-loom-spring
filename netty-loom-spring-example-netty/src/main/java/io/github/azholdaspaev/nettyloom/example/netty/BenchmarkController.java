package io.github.azholdaspaev.nettyloom.example.netty;

import io.github.azholdaspaev.nettyloom.example.netty.dto.DelayResponse;
import io.github.azholdaspaev.nettyloom.example.netty.dto.EchoRequest;
import io.github.azholdaspaev.nettyloom.example.netty.dto.EchoResponse;
import io.github.azholdaspaev.nettyloom.example.netty.dto.JsonResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private static final String SERVER_NAME = "netty";

    @GetMapping("/json")
    public JsonResponse json() {
        return new JsonResponse("Hello, World!", System.currentTimeMillis(), SERVER_NAME);
    }

    @PostMapping("/echo")
    public EchoResponse echo(@RequestBody EchoRequest request) {
        return new EchoResponse(request.data(), request.count(), SERVER_NAME);
    }

    @GetMapping("/delay")
    public DelayResponse delay(@RequestParam(defaultValue = "100") long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new DelayResponse(ms, Thread.currentThread().toString(), SERVER_NAME);
    }
}
