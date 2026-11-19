package com.example.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads packaged HTML shared by the backend console and proxy dashboard. */
public final class PageTemplate {
    private PageTemplate() { }

    public static String load(String resource) {
        try (var input = PageTemplate.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing packaged page template: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read packaged page template: " + resource, error);
        }
    }
}
