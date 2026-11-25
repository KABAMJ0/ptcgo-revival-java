package com.ptcgo.revival.progression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/** Versus ladder seasons (ptcgo/game/progression/seasons.py + models/versus.py). */
public final class Seasons {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ZERO_GUID = "00000000-0000-0000-0000-000000000000";

    /** A reward as stored in versus_seasons.json (kept as raw JSON; only the fields below are interpreted). */
    public record Season(String seasonId, long startTime, long endTime, JsonNode description, JsonNode raw) {
        /** {threshold points: rewards} flattened across all tiers, ascending. */
        public Map<Integer, List<JsonNode>> thresholds() {
            Map<Integer, List<JsonNode>> m = new TreeMap<>();
            for (JsonNode tier : raw.path("tiers")) {
                tier.path("rewards").fields().forEachRemaining(e -> {
                    int pts;
                    try {
                        pts = Integer.parseInt(e.getKey());
                    } catch (NumberFormatException ex) {
                        pts = 0;
                    }
                    List<JsonNode> list = m.computeIfAbsent(pts, k -> new ArrayList<>());
                    e.getValue().forEach(list::add);
                });
            }
            return m;
        }
    }

    private final List<Season> seasons;
    private final LongSupplier clockMs;

    public Seasons(List<Season> seasons, LongSupplier clockMs) {
        this.seasons = seasons;
        this.clockMs = clockMs;
    }

    public static Seasons loadBundled() {
        try (InputStream in = Seasons.class.getResourceAsStream("/data/versus_seasons.json")) {
            return parse(JSON.readTree(in), System::currentTimeMillis);
        } catch (IOException e) {
            throw new IllegalStateException("versus_seasons.json unreadable", e);
        }
    }

    public static Seasons parse(JsonNode array, LongSupplier clockMs) {
        List<Season> list = new ArrayList<>();
        for (JsonNode s : array) {
            list.add(new Season(s.path("seasonID").asText(""), s.path("startTime").asLong(0), s.path("endTime").asLong(0),
                    s.has("description") ? s.get("description") : JSON.createObjectNode().put("id", "PTCGO Revival Season"), s));
        }
        return new Seasons(list, clockMs);
    }

    /** The season covering now, else the first one, else null. */
    public Season active() {
        long now = clockMs.getAsLong();
        for (Season s : seasons) if (s.startTime() <= now && now <= s.endTime()) return s;
        return seasons.isEmpty() ? null : seasons.get(0);
    }

    public String activeId() {
        Season s = active();
        return s == null ? "" : s.seasonId();
    }

    /**
     * CurrentVersusSeason payload. A 0 start/end makes the client pop its new-season dialog on every login, and
     * each tier's rewards are ordered cards first, then products, tokens last (the client indexes reward[0]).
     */
    public ObjectNode clientPayload(Catalog catalog) {
        Season s = active();
        if (s == null) {
            ObjectNode f = JSON.createObjectNode();
            f.put("seasonID", "Season1").put("startTime", 1).put("endTime", 4102444800000L);
            f.putObject("description").put("id", "PTCGO Revival Season");
            f.putArray("tiers");
            f.put("resetRewardID", "");
            return f;
        }
        long start = Math.max(1, s.startTime());
        ObjectNode p = JSON.createObjectNode();
        p.put("seasonID", s.seasonId()).put("startTime", start).put("endTime", Math.max(start + 1, s.endTime()));
        p.set("description", s.description());
        ArrayNode tiers = p.putArray("tiers");
        for (JsonNode tier : s.raw().path("tiers")) {
            ObjectNode t = tiers.addObject();
            ObjectNode rewards = t.putObject("rewards");
            tier.path("rewards").fields().forEachRemaining(e -> {
                List<JsonNode> ordered = new ArrayList<>();
                e.getValue().forEach(ordered::add);
                ordered.sort(java.util.Comparator.comparingInt(r -> rank(r, catalog)));
                ArrayNode out = rewards.putArray(e.getKey());
                for (int i = 0; i < ordered.size(); i++) out.add(reward(ordered.get(i), i));
            });
        }
        p.put("resetRewardID", s.raw().path("resetRewardID").asText(""));
        return p;
    }

    private static int rank(JsonNode reward, Catalog catalog) {
        String guid = reward.path("rewardProductID").asText("").toLowerCase(Locale.ROOT);
        if (guid.isEmpty() || guid.equals(ZERO_GUID)) return 2;
        return catalog.card(guid) != null ? 0 : 1;
    }

    /** Reward.to_dict: rewardProductID is null when there is no product (the client dereferences it unguarded). */
    static ObjectNode reward(JsonNode r, int index) {
        String product = r.path("rewardProductID").asText("");
        ObjectNode o = JSON.createObjectNode();
        o.put("name", r.path("name").asText("")).put("rewardType", r.path("rewardType").asText("Tokens"))
                .put("rewardAmount", r.path("rewardAmount").asInt(0));
        if (product.isEmpty() || product.equals(ZERO_GUID)) o.putNull("rewardProductID"); else o.put("rewardProductID", product);
        o.put("rewardCurrency", r.path("rewardCurrency").asText(""));
        o.set("rewardDescription", r.hasNonNull("rewardDescription") ? r.get("rewardDescription") : JSON.createObjectNode().put("id", ""));
        o.put("rewardReason", r.path("rewardReason").asText("")).put("index", index);
        return o;
    }
}
