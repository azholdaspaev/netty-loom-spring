package io.github.azholdaspaev.nettyloom.example.tomcat.dto;

public record DelayResponse(long delayMs, String threadName, String server) {}
