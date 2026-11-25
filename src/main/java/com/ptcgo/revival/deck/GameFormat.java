package com.ptcgo.revival.deck;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One play format: the sets and per-card exceptions that are legal in it (an entry of formats.json).
 * Card refs are a bare archetype guid or {@code SETCODE/collector_number}; {@code legalFrom} maps a set code to the
 * epoch millisecond it becomes legal (absent = always).
 */
public record GameFormat(String key, String guid, String formatName, Set<String> sets, boolean allSets,
                         List<String> bannedCards, List<String> extraLegalCards, Map<String, Long> legalFrom) {

    public GameFormat {
        guid = guid.toLowerCase(Locale.ROOT);
        sets = Set.copyOf(sets);
        bannedCards = List.copyOf(bannedCards);
        extraLegalCards = List.copyOf(extraLegalCards);
        legalFrom = Map.copyOf(legalFrom);
    }

    /** A format with no card exceptions and no time gates. */
    public static GameFormat of(String key, String guid, String formatName, Set<String> sets, boolean allSets) {
        return new GameFormat(key, guid, formatName, sets, allSets, List.of(), List.of(), Map.of());
    }

    /** Parses one formats.json entry; {@link IllegalArgumentException} when it is malformed. */
    public static GameFormat fromJson(JsonNode n) {
        String key = n.path("key").asText("");
        if (key.isEmpty()) throw new IllegalArgumentException("format entry needs a non-empty 'key'");
        String guid = n.path("guid").asText("");
        if (guid.isEmpty()) throw new IllegalArgumentException("format '" + key + "' needs a 'guid'");
        UUID.fromString(guid);
        String name = n.path("formatName").asText("");
        if (name.isEmpty()) name = FormatManager.WIRE_NAMES.getOrDefault(guid.toLowerCase(Locale.ROOT), key);
        Map<String, Long> legalFrom = new HashMap<>();
        JsonNode lf = n.path("legalFrom");
        if (!lf.isMissingNode() && !lf.isObject()) {
            throw new IllegalArgumentException("format '" + key + "': 'legalFrom' must be an object");
        }
        lf.fields().forEachRemaining(e -> legalFrom.put(e.getKey(), e.getValue().asLong()));
        return new GameFormat(key, guid, name, new HashSet<>(strings(key, "sets", n.path("sets"))),
                n.path("allSets").asBoolean(false), strings(key, "bannedCards", n.path("bannedCards")),
                strings(key, "extraLegalCards", n.path("extraLegalCards")), legalFrom);
    }

    private static List<String> strings(String key, String field, JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isMissingNode()) return out;
        if (!array.isArray()) throw new IllegalArgumentException("format '" + key + "': '" + field + "' must be a list");
        for (JsonNode e : array) {
            if (!e.isTextual()) throw new IllegalArgumentException("format '" + key + "': '" + field + "' must hold strings");
            out.add(e.asText());
        }
        return out;
    }

    public boolean allowsSet(String setCode) {
        return allSets || sets.contains(setCode);
    }
}
