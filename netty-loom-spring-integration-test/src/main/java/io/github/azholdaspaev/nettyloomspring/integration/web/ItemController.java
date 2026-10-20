package io.github.azholdaspaev.nettyloomspring.integration.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/items")
public class ItemController {

    @GetMapping("/{id}")
    public String findItem(@PathVariable String id) {
        return id;
    }

    @GetMapping("/{id}/unavailable")
    public String failUnavailable(@PathVariable String id) {
        throw new ItemUnavailableException(id);
    }
}
