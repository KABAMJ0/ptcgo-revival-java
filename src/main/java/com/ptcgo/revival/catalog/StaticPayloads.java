package com.ptcgo.revival.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Payloads the Python exporter captured under {@code <catalog>/payloads/*.json}; read lazily and cached. */
public final class StaticPayloads {
    private static final Logger LOG = LoggerFactory.getLogger(StaticPayloads.class);
    private final Path dir;
    private final Map<String, Optional<JsonNode>> cache = new ConcurrentHashMap<>();

    public StaticPayloads(Path dir) {
        this.dir = dir;
    }

    /** The parsed file ({@code name} without ".json"), or empty if it was not exported. */
    public Optional<JsonNode> get(String name) {
        return cache.computeIfAbsent(name, n -> {
            if (dir == null) return Optional.empty();
            Path f = dir.resolve(n + ".json");
            if (!Files.isRegularFile(f)) {
                LOG.warn("[Catalog] payload {} not found", f);
                return Optional.empty();
            }
            try {
                return Optional.of(Catalog.JSON.readTree(f.toFile()));
            } catch (IOException e) {
                LOG.error("[Catalog] cannot read payload {}", f, e);
                return Optional.empty();
            }
        });
    }
}
