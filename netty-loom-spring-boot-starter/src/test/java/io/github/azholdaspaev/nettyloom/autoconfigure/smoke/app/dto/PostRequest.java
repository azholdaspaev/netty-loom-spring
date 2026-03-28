package io.github.azholdaspaev.nettyloom.autoconfigure.smoke.app.dto;

import java.util.List;

public record PostRequest(
    String name,
    List<Long> items
) {
}
