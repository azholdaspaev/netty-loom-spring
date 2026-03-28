package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto;

import java.util.List;

public record PostResponse(Long id, String name, List<Long> items) {}
