package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto;

import java.util.List;

public record GetResponse(
    Long id,
    String name,
    List<String> items
) {
}
