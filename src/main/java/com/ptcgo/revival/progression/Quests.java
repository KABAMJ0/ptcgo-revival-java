package com.ptcgo.revival.progression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Daily challenge templates and the client payloads built from them (ptcgo/game/progression/quests.py). */
public final class Quests {
    public static final int MAX_ACTIVE = 3;
    public static final Map<Integer, Integer> XP_LEVELS = new TreeMap<>(Map.of(1, 5, 2, 10, 3, 20, 4, 35, 5, 50));
    public static final List<String> AFFINITIES = List.of("Colorless", "Darkness", "Dragon", "Fairy", "Fighting",
            "Fire", "Grass", "Lightning", "Metal", "Psychic", "Water");
    static final Set<String> STATS = Set.of("wins", "damagedealt", "energyplayed", "trainersplayed", "prizecardstaken");
    /** The client has no QuestCompleted string, so the reward label rides as text. */
    public static final String QUEST_COMPLETED_TEXT = "Challenge Completed";

    private static final ObjectMapper JSON = new ObjectMapper();

    private Quests() {}

    /** A challenge template (title, description, stat to count, target, coin and xp rewards). */
    public record Template(String key, String title, String description, String stat, int target, int coins, int xp) {
        public ObjectNode toJson() {
            ObjectNode n = JSON.createObjectNode();
            n.put("key", key).put("title", title).put("description", description).put("stat", stat)
                    .put("target", target).put("coins", coins).put("xp", xp);
            return n;
        }

        public static Template fromJson(JsonNode n) {
            return new Template(n.path("key").asText(), n.path("title").asText(), n.path("description").asText(),
                    n.path("stat").asText(), n.path("target").asInt(), n.path("coins").asInt(), n.path("xp").asInt());
        }
    }

    /** Loads and validates the bundled templates; the rules mirror Python's load_catalog. */
    public static List<Template> loadCatalog() {
        try (InputStream in = Quests.class.getResourceAsStream("/data/daily_challenges.json")) {
            return parse(JSON.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("daily_challenges.json unreadable", e);
        }
    }

    static List<Template> parse(JsonNode rows) {
        List<Template> out = new ArrayList<>();
        Set<String> keys = new java.util.HashSet<>();
        for (JsonNode r : rows) {
            String key = r.path("key").asText("");
            if (key.isEmpty()) throw new IllegalArgumentException("Each daily challenge needs a nonempty key");
            if (!keys.add(key) || !STATS.contains(r.path("stat").asText())) {
                throw new IllegalArgumentException("Duplicate key or unsupported stat: " + key);
            }
            for (String f : List.of("title", "description")) {
                if (r.path(f).asText("").isEmpty()) throw new IllegalArgumentException("Challenge " + key + " needs " + f);
            }
            for (Object[] f : new Object[][] {{"target", 1}, {"coins", 1}, {"xp", 0}}) {
                JsonNode v = r.path((String) f[0]);
                if (!v.isInt() || v.asInt() < (int) f[1] || v.asInt() > 100000) {
                    throw new IllegalArgumentException("Invalid " + f[0] + " for challenge " + key);
                }
            }
            out.add(Template.fromJson(r));
        }
        if (out.size() < 2) throw new IllegalArgumentException("Daily challenges need at least two templates");
        return out;
    }

    public static ObjectNode coinReward(int amount, String source) {
        ObjectNode r = JSON.createObjectNode();
        r.put("name", "DailyChallengeReward").put("rewardType", "Tokens").put("rewardAmount", amount);
        r.putNull("rewardProductID");
        r.put("rewardCurrency", "Tokens");
        r.putObject("rewardDescription").put("id", QUEST_COMPLETED_TEXT);
        r.put("rewardReason", "QuestCompleted");
        r.putArray("selectedFrom");
        r.put("selectedIndex", 0).put("rewardSource", source).put("index", 0);
        r.putNull("openedReward");
        return r;
    }

    public static ObjectNode questPayload(String questId, Template t, int activations) {
        ObjectNode def = JSON.createObjectNode();
        def.put("questID", questId).put("name", "PTCGODaily_" + questId);
        def.putObject("title").put("id", t.title());
        def.putObject("description").put("id", t.description());
        def.putArray("rewards").add(coinReward(t.coins(), questId));
        def.put("affinity", "Colorless").put("xp", t.xp()).put("tier", 1);
        def.putNull("startTime");
        def.putNull("endTime");
        def.put("priority", "Bronze").put("abandonable", true);
        def.putArray("gameModes").add("PvPMatch").add("TournamentMatch");
        def.putObject("questInformation").put("id", "Progress counts in multiplayer matches started after accepting. "
                + "Unfinished challenges carry over; choose one new challenge per UTC day.");
        ObjectNode progress = JSON.createObjectNode();
        progress.put("questID", questId).put("activations", activations).put("requiredActivations", t.target());
        ObjectNode payload = JSON.createObjectNode();
        payload.set("questDefinition", def);
        payload.set("currentProgress", progress);
        return payload;
    }

    public static ObjectNode configuration(long nextAvailableMs) {
        ObjectNode c = JSON.createObjectNode();
        ObjectNode xp = c.putObject("xpLevelMap");
        XP_LEVELS.forEach((k, v) -> xp.put(String.valueOf(k), v));
        ObjectNode tiers = c.putObject("levelTierMap");
        for (String k : List.of("0", "3", "5", "8", "10")) tiers.put(k, 1);
        ObjectNode rewards = c.putObject("affinityToAffinityLevelRewardsMap");
        for (String affinity : AFFINITIES) {
            ObjectNode perLevel = rewards.putObject(affinity);
            XP_LEVELS.keySet().forEach(level -> perLevel.putArray(String.valueOf(level)).add(coinReward(25, "")));
        }
        c.put("nextQuestAvailableTime", nextAvailableMs);
        ObjectNode active = c.putObject("levelActiveQuestsMap");
        active.put("1", -1).put("2", -1).put("3", 2147483647);
        return c;
    }

    /** {affinity: xp} with every affinity present, as attribute 201790 carries it. */
    public static Map<String, Object> xpAttribute(Map<String, Integer> stored) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String a : AFFINITIES) m.put(a, stored.getOrDefault(a, 0));
        return m;
    }

    static ArrayNode array() {
        return JSON.createArrayNode();
    }
}
