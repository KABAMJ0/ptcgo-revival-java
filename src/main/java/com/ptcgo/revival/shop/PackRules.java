package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.Catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-set booster rules (pack_rules.json): slot counts, guaranteed cards, excluded-from-random names, weight
 * multipliers and unique-per-pack names. Mirrors ptcgo/game/content/pack_rules.py.
 */
public final class PackRules {
    private final Map<String, JsonNode> rules = new HashMap<>();
    /** set code (upper) -> script stem -> card guid, for weight_multiplier keys that are one printing's script name. */
    private final Map<String, Map<String, String>> stemGuids = new HashMap<>();

    public PackRules(JsonNode rulesJson, JsonNode stemsJson) {
        if (rulesJson != null) {
            rulesJson.fields().forEachRemaining(e -> {
                if (e.getValue().isObject()) rules.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
            });
        }
        if (stemsJson != null) {
            stemsJson.fields().forEachRemaining(e -> {
                if (!e.getValue().isObject()) return;
                Map<String, String> m = new HashMap<>();
                e.getValue().fields().forEachRemaining(s -> m.put(s.getKey(), s.getValue().asText().toLowerCase(Locale.ROOT)));
                stemGuids.put(e.getKey().toUpperCase(Locale.ROOT), m);
            });
        }
    }

    public static PackRules none() {
        return new PackRules(null, null);
    }

    /** The rules shipped with the server. */
    public static PackRules defaults() {
        return new PackRules(Resources.read("pack_rules.json"), Resources.read("pack_stems.json"));
    }

    /** The set's rules object, or an empty object. Empty (falsy in Python) when the set has none. */
    public JsonNode rulesFor(String setKey) {
        JsonNode n = rules.get((setKey == null ? "" : setKey).toUpperCase(Locale.ROOT));
        return n == null ? Resources.JSON.createObjectNode() : n;
    }

    public boolean hasRules(String setKey) {
        return rules.containsKey((setKey == null ? "" : setKey).toUpperCase(Locale.ROOT));
    }

    public static String nameOf(Catalog.Card card) {
        return card.displayName() == null ? "" : card.displayName().strip();
    }

    /** Script file name (without .py) of a card: an explicit stem entry, else "<Name without punctuation>_<number>". */
    private String stemOf(String setKey, Catalog.Card card) {
        Map<String, String> explicit = stemGuids.get(setKey.toUpperCase(Locale.ROOT));
        if (explicit != null) {
            for (Map.Entry<String, String> e : explicit.entrySet()) if (e.getValue().equals(card.guid())) return e.getKey();
        }
        return card.displayName().replaceAll("[^A-Za-z0-9]", "") + "_" + card.collectorNumber();
    }

    /** Rarity-draw weight multiplier for one card; 1.0 when the set has no rule for it. */
    public double multiplierFor(String setKey, Catalog.Card card) {
        JsonNode table = rulesFor(setKey).path("weight_multiplier");
        if (!table.isObject() || table.isEmpty()) return 1.0;
        String stem = stemOf(setKey, card);
        if (table.has(stem)) return table.get(stem).asDouble(1.0);
        return table.path(nameOf(card)).asDouble(1.0);
    }

    /** One candidate pool per guaranteed slot. */
    public List<List<Catalog.Card>> guaranteedPicks(String setKey, List<Catalog.Card> cards) {
        List<List<Catalog.Card>> picks = new ArrayList<>();
        for (JsonNode slot : rulesFor(setKey).path("guaranteed")) {
            Set<String> names = strings(slot.path("names"));
            int count = slot.path("count").asInt(1);
            if (count == 0) count = 1; // Python: int(slot.get("count") or 1)
            List<Catalog.Card> pool = new ArrayList<>();
            for (Catalog.Card c : cards) if (names.contains(nameOf(c))) pool.add(c);
            if (pool.isEmpty()) continue;
            for (int i = 0; i < count; i++) picks.add(pool);
        }
        return picks;
    }

    public Set<String> uniquePerPack(String setKey) {
        return strings(rulesFor(setKey).path("unique_per_pack"));
    }

    public Set<String> excludeFromRandom(String setKey) {
        return strings(rulesFor(setKey).path("exclude_from_random"));
    }

    /** Drops later copies of a card whose name may appear only once per pack. */
    public List<String> dedupe(List<String> pack, Map<String, Catalog.Card> cardsByGuid, String setKey) {
        Set<String> unique = uniquePerPack(setKey);
        if (unique.isEmpty()) return pack;
        Set<String> seen = new HashSet<>();
        List<String> kept = new ArrayList<>();
        for (String guid : pack) {
            Catalog.Card card = cardsByGuid.get(guid);
            String name = card == null ? null : nameOf(card);
            if (name != null && unique.contains(name) && !seen.add(name)) continue;
            kept.add(guid);
        }
        return kept;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> s = new HashSet<>();
        for (JsonNode n : array) s.add(n.asText().strip());
        return s;
    }
}
