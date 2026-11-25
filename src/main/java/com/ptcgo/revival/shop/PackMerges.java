package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * Sets that are not their own booster pack (pack_merges.json): {@code no_pack} sets are neither sold nor handed out;
 * a {@code merge} set (Trainer Gallery, Shiny Vault ...) can replace the reverse-holo slot of its parent's pack.
 */
public final class PackMerges {
    public record Merge(String into, double chance) {}

    private final Set<String> noPack = new HashSet<>();
    private final Map<String, Merge> merge = new LinkedHashMap<>();

    public PackMerges(JsonNode json) {
        if (json == null) return;
        json.path("no_pack").forEach(c -> noPack.add(c.asText().toUpperCase(Locale.ROOT)));
        json.path("merge").fields().forEachRemaining(e -> {
            JsonNode rule = e.getValue();
            if (!rule.hasNonNull("into")) return;
            double chance = Math.max(0.0, Math.min(1.0, rule.path("chance").asDouble(0.25)));
            merge.put(e.getKey().toUpperCase(Locale.ROOT), new Merge(rule.get("into").asText().toUpperCase(Locale.ROOT), chance));
        });
    }

    public static PackMerges none() {
        return new PackMerges(null);
    }

    public static PackMerges defaults() {
        return new PackMerges(Resources.read("pack_merges.json"));
    }

    /** True when this set must not be sold or handed out as its own pack. */
    public boolean isHiddenPack(String setKey) {
        String key = setKey == null ? "" : setKey.toUpperCase(Locale.ROOT);
        return noPack.contains(key) || merge.containsKey(key);
    }

    /** Source sets (and chances) merged into this set's packs, in file order. */
    public List<Map.Entry<String, Double>> sourcesFor(String setKey) {
        String key = setKey == null ? "" : setKey.toUpperCase(Locale.ROOT);
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        merge.forEach((src, m) -> {
            if (m.into().equals(key)) out.add(Map.entry(src, m.chance()));
        });
        return out;
    }

    /** Possibly swaps the reverse-holo slot (second to last) for a merged-in card; one swap per pack at most. */
    public List<String> applyInserts(String setKey, List<String> pack, Function<String, List<String>> poolFor,
                                     RandomGenerator rng) {
        List<String> out = new ArrayList<>(pack);
        if (out.size() < 2) return out;
        for (Map.Entry<String, Double> s : sourcesFor(setKey)) {
            if (rng.nextDouble() >= s.getValue()) continue;
            List<String> pool = poolFor.apply(s.getKey());
            if (!pool.isEmpty()) {
                out.set(out.size() - 2, pool.get(rng.nextInt(pool.size())));
                break;
            }
        }
        return out;
    }
}
