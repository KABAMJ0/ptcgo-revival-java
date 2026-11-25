package com.ptcgo.revival.progression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Daily login reward tracks (ptcgo/game/progression/daily_rewards.py). */
public final class DailyRewards {
    /** The client shows the 3-slot newbie dialog while activations <= 3, the 5-slot one after. */
    public static final int NEWBIE_MAX_ACTIVATIONS = 3;
    static final Map<String, Integer> TRACK_DAYS = Map.of("newbie", 3, "standard", 5);
    /** Any other rewardType renders an UnSet slot with amount -1 on the client. */
    static final Set<String> VALID_TYPES = Set.of("Tokens", "TournamentTicket", "RandomBooster", "Archetype");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, List<List<JsonNode>>> tracks;

    private DailyRewards(Map<String, List<List<JsonNode>>> tracks) {
        this.tracks = tracks;
    }

    public static DailyRewards loadBundled() {
        try (InputStream in = DailyRewards.class.getResourceAsStream("/data/daily_rewards.json")) {
            return parse(JSON.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("daily_rewards.json unreadable", e);
        }
    }

    /** Validates {track: [day: [reward, ...]]}; the rules mirror Python's parse_tracks. */
    public static DailyRewards parse(JsonNode data) {
        Map<String, List<List<JsonNode>>> out = new java.util.HashMap<>();
        for (Map.Entry<String, Integer> t : TRACK_DAYS.entrySet()) {
            JsonNode days = data.get(t.getKey());
            if (days == null || !days.isArray() || days.size() != t.getValue()) {
                throw new IllegalArgumentException("track '" + t.getKey() + "' must have exactly " + t.getValue() + " days");
            }
            List<List<JsonNode>> parsed = new ArrayList<>();
            int dayNo = 0;
            for (JsonNode day : days) {
                dayNo++;
                if (!day.isArray() || day.isEmpty()) {
                    throw new IllegalArgumentException("track '" + t.getKey() + "' day " + dayNo + " must be a non-empty list");
                }
                List<JsonNode> rewards = new ArrayList<>();
                for (JsonNode r : day) {
                    String type = r.path("rewardType").asText("Tokens");
                    if (!VALID_TYPES.contains(type)) {
                        throw new IllegalArgumentException("track '" + t.getKey() + "' day " + dayNo + ": invalid rewardType '" + type + "'");
                    }
                    if (type.equals("Archetype") && r.path("rewardProductID").asText("").isEmpty()) {
                        throw new IllegalArgumentException("track '" + t.getKey() + "' day " + dayNo + ": Archetype reward needs rewardProductID");
                    }
                    rewards.add(r);
                }
                // The client sums a multi-reward day only when every entry is an Archetype.
                if (rewards.size() > 1 && rewards.stream().anyMatch(r -> !r.path("rewardType").asText("Tokens").equals("Archetype"))) {
                    throw new IllegalArgumentException("track '" + t.getKey() + "' day " + dayNo + ": multi-reward days must be all-Archetype");
                }
                parsed.add(rewards);
            }
            out.put(t.getKey(), parsed);
        }
        return new DailyRewards(out);
    }

    public boolean isNewbie(int activations) {
        return activations <= NEWBIE_MAX_ACTIVATIONS;
    }

    List<List<JsonNode>> trackFor(int activations) {
        return tracks.get(isNewbie(activations) ? "newbie" : "standard");
    }

    /** 1-indexed slot in the displayed track; wraps weekly. */
    public int rewardDay(int streak, int activations) {
        return (Math.max(streak, 1) - 1) % trackFor(activations).size() + 1;
    }

    public List<JsonNode> rewardsFor(int streak, int activations) {
        return trackFor(activations).get(rewardDay(streak, activations) - 1);
    }

    /** DailyLogin.weeksRewards: outer array = days, inner = that day's rewards. */
    public ArrayNode weeksRewards(int activations) {
        ArrayNode all = JSON.createArrayNode();
        for (List<JsonNode> day : trackFor(activations)) {
            ArrayNode d = all.addArray();
            for (int i = 0; i < day.size(); i++) d.add(Seasons.reward(day.get(i), i));
        }
        return all;
    }
}
