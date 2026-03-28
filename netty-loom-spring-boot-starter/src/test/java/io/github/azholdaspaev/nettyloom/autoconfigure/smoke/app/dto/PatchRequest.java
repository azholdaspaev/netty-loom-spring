package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto;

import java.util.List;

public record PatchRequest(String name, List<Long> items) {}
