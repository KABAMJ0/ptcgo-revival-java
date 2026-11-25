package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which cards get a holo and with which effect (foil_rules.json). In the Python server this decides the card's foil
 * attribute at export time, not at pack opening; it is ported so tools and tests can ask the same question of an
 * opened card. Mirrors ptcgo/game/content/foil_rules.py (gets_foil, rule_for, effect_for).
 */
public final class FoilRules {
    private final JsonNode data;

    public FoilRules(JsonNode data) {
        this.data = data == null ? Resources.JSON.createObjectNode() : data;
    }

    public static FoilRules defaults() {
        return new FoilRules(Resources.read("foil_rules.json"));
    }

    public record Effect(String name, Integer intensity) {}

    public boolean getsFoil(String rarityName, String setCode, List<String> subtypes) {
        Set<String> have = lower(subtypes);
        for (JsonNode r : data.path("foil_rarities")) if (r.asText().equals(rarityName)) return true;
        for (JsonNode rule : data.path("foil_extra")) {
            if (!regexOk(rule, setCode)) continue;
            if (rule.hasNonNull("rarity") && !contains(rule.get("rarity"), rarityName)) continue;
            if (rule.hasNonNull("subtype") && !intersects(have, rule.get("subtype"))) continue;
            if (rule.hasNonNull("rarity") || rule.hasNonNull("subtype")) return true;
        }
        return false;
    }

    /** The first matching effect rule, or the default rule (an empty object when none). */
    public JsonNode ruleFor(String rarityName, String setCode, List<String> subtypes) {
        String code = setCode == null ? "" : setCode;
        Set<String> have = lower(subtypes);
        for (JsonNode rule : data.path("rules")) {
            if (rule.hasNonNull("sets") && !containsIgnoreCase(rule.get("sets"), code)) continue;
            if (!regexOk(rule, code)) continue;
            if (rule.hasNonNull("rarity") && !contains(rule.get("rarity"), rarityName)) continue;
            if (rule.hasNonNull("subtype") && !intersects(have, rule.get("subtype"))) continue;
            return rule;
        }
        return data.path("default");
    }

    public Effect effectFor(String rarityName, String setCode, List<String> subtypes) {
        JsonNode rule = ruleFor(rarityName, setCode, subtypes);
        return new Effect(rule.path("effect").asText("SWHOLO"), rule.hasNonNull("intensity") ? rule.get("intensity").asInt() : null);
    }

    private static boolean regexOk(JsonNode rule, String setCode) {
        if (!rule.hasNonNull("set_regex")) return true;
        return Pattern.compile(rule.get("set_regex").asText(), Pattern.CASE_INSENSITIVE)
                .matcher(setCode == null ? "" : setCode).find();
    }

    private static boolean contains(JsonNode array, String v) {
        for (JsonNode n : array) if (n.asText().equals(v)) return true;
        return false;
    }

    private static boolean containsIgnoreCase(JsonNode array, String v) {
        for (JsonNode n : array) if (n.asText().equalsIgnoreCase(v)) return true;
        return false;
    }

    private static boolean intersects(Set<String> have, JsonNode array) {
        for (JsonNode n : array) if (have.contains(n.asText().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static Set<String> lower(List<String> subtypes) {
        Set<String> s = new HashSet<>();
        if (subtypes != null) for (String t : subtypes) s.add(t.toLowerCase(Locale.ROOT));
        return s;
    }
}
