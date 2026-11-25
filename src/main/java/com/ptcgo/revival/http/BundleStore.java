package com.ptcgo.revival.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bundle files written by the Python {@code tools/export_bundles.py} into {@code PTCGO_BUNDLE_DIR}.
 * Only names listed in {@code manifest.json} are servable; the lookup is by plain file name, so nothing
 * outside the list (or outside the directory) is ever opened.
 *
 * <p>manifest.json: {@code {"files": [{"name": "...", "size": 123, "sha256": "...", "content_type": "..."}]}}.
 * {@code name} must be a bare file name (no separators). Only {@code name} and {@code size} are used at serve
 * time; {@code sha256} and {@code content_type} are for the exporter and audits.
 */
final class BundleStore {
    private static final Logger LOG = LoggerFactory.getLogger(BundleStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    record Entry(String name, long size) {}

    private final Path dir;
    private final Map<String, Entry> entries;

    private BundleStore(Path dir, Map<String, Entry> entries) {
        this.dir = dir;
        this.entries = entries;
    }

    /** Reads {@code <dir>/manifest.json}. A missing manifest gives an empty store (every bundle route 404s). */
    static BundleStore load(Path dir) throws IOException {
        Path manifest = dir.resolve("manifest.json");
        Map<String, Entry> entries = new HashMap<>();
        if (!Files.isRegularFile(manifest)) {
            LOG.info("[HTTP] No bundle manifest at {}; bundle routes answer 404", manifest);
            return new BundleStore(dir, Map.of());
        }
        JsonNode files = JSON.readTree(Files.readAllBytes(manifest)).path("files");
        for (JsonNode f : files) {
            String name = f.path("name").asText("");
            if (!isBareName(name)) {
                LOG.warn("[HTTP] Ignoring bundle manifest entry with unsafe name '{}'", name);
                continue;
            }
            entries.put(name, new Entry(name, f.path("size").asLong(-1)));
        }
        LOG.info("[HTTP] Bundle manifest {} lists {} files", manifest, entries.size());
        return new BundleStore(dir, Map.copyOf(entries));
    }

    Optional<Entry> find(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    Path file(Entry entry) {
        return dir.resolve(entry.name());
    }

    private static boolean isBareName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }
}
