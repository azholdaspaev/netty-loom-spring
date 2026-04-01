package io.github.azholdaspaev.nettyloom.example.netty.dto;

public record DelayResponse(long delayMs, String threadName, String server) {}
