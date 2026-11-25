package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The static rule files shipped on the classpath under /shop (copied from the Python server's json_data). */
final class Resources {
    static final ObjectMapper JSON = new ObjectMapper();

    private Resources() {}

    static JsonNode read(String name) {
        try (InputStream in = Resources.class.getResourceAsStream("/shop/" + name)) {
            if (in == null) throw new IllegalStateException("missing resource /shop/" + name);
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
