package com.ptcgo.revival.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Card catalogue loaded from the JSON produced by the Python exporter (tools/export_catalog.py):
 * {@code <dir>/cards/<SET>.json}, each a list of {guid, key, display_name, set_code, collector_number, kind,
 * searchable_by, subtypes, attributes, wire_attributes}. Effects are code and are not part of the catalogue.
 */
public final class Catalog {
    private static final Logger LOG = LoggerFactory.getLogger(Catalog.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code wire} is the typed attribute map the client is sent ("wire_attributes", else "attributes"). */
    public record Card(String guid, String key, String displayName, String setCode, int collectorNumber, String kind,
                       JsonNode wire) {}

    private final Map<String, Card> byGuid = new LinkedHashMap<>();
    private final Map<String, Set<String>> guidsBySet = new TreeMap<>();

    public static Catalog empty() {
        return new Catalog();
    }

    /** Loads every cards/*.json under {@code dir}; a missing directory yields an empty catalogue. */
    public static Catalog load(Path dir) throws IOException {
        Catalog c = new Catalog();
        Path cards = dir.resolve("cards");
        if (!Files.isDirectory(cards)) {
            LOG.warn("[Catalog] {} not found; starting with an empty catalogue", cards);
            return c;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cards, "*.json")) {
            for (Path f : files) {
                JsonNode list = JSON.readTree(f.toFile());
                for (JsonNode n : list) {
                    String guid = n.path("guid").asText("").toLowerCase(Locale.ROOT);
                    if (guid.isEmpty()) continue;
                    Card card = new Card(guid, n.path("key").asText(""), n.path("display_name").asText(""),
                            n.path("set_code").asText(n.path("key").asText("")), n.path("collector_number").asInt(),
                            n.path("kind").asText(""), n.has("wire_attributes") ? n.get("wire_attributes") : n.path("attributes"));
                    c.byGuid.put(guid, card);
                    c.guidsBySet.computeIfAbsent(card.setCode(), k -> new LinkedHashSet<>()).add(guid);
                }
            }
        }
        LOG.info("[Catalog] Loaded {} cards in {} sets", c.byGuid.size(), c.guidsBySet.size());
        return c;
    }

    public int size() {
        return byGuid.size();
    }

    public Card card(String guid) {
        return guid == null ? null : byGuid.get(guid.toLowerCase(Locale.ROOT));
    }

    public Set<String> setCodes() {
        return guidsBySet.keySet();
    }

    public Set<String> guidsInSet(String setCode) {
        return guidsBySet.getOrDefault(setCode, Set.of());
    }

    /** Basic energy every player owns unlimited copies of. */
    public Set<String> freeEnergyGuids() {
        return guidsInSet("Free_Energy");
    }
}
