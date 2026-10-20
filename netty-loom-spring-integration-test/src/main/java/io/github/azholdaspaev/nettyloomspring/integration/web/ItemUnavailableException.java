package io.github.azholdaspaev.nettyloomspring.integration.web;

public class ItemUnavailableException extends RuntimeException {

    public ItemUnavailableException(String id) {
        super("item " + id + " is unavailable");
    }
}
