package io.github.azholdaspaev.nettyloomspring.integration.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/items")
public class ItemController {

    private static final Logger log = LoggerFactory.getLogger(ItemController.class);

    @GetMapping("/{id}")
    public String findItem(@PathVariable String id) {
        log.info("Finding item {}", id);
        return id;
    }

    @GetMapping("/{id}/unavailable")
    public String failUnavailable(@PathVariable String id) {
        throw new ItemUnavailableException(id);
    }
}
