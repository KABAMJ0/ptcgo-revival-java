package com.ptcgo.revival.progression;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptcgo.revival.catalog.Catalog;
import org.junit.jupiter.api.Test;

class SeasonsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void bundledSeasonsLoadAndActiveSeasonCoversNow() {
        Seasons s = Seasons.loadBundled();
        assertNotNull(s.active());
        JsonNode p = s.clientPayload(Catalog.empty());
        assertTrue(p.get("startTime").asLong() >= 1, "0 would pop the new-season dialog on every login");
        assertTrue(p.get("endTime").asLong() > p.get("startTime").asLong());
    }

    @Test
    void fallsBackToFirstSeasonAndOrdersRewardsCardsFirst() throws Exception {
        JsonNode raw = JSON.readTree("""
                [{"seasonID":"S1","startTime":10,"endTime":20,"tiers":[{"rewards":{"5":[
                  {"name":"tok","rewardType":"Tokens","rewardAmount":5,"rewardProductID":null},
                  {"name":"prod","rewardType":"Archetype","rewardAmount":1,"rewardProductID":"11111111-1111-1111-1111-111111111111"},
                  {"name":"card","rewardType":"Archetype","rewardAmount":1,"rewardProductID":"22222222-2222-2222-2222-222222222222"}]}}]}]""");
        Seasons s = Seasons.parse(raw, () -> 999L); // outside every window: first season is the fallback
        assertEquals("S1", s.activeId());
        Catalog catalog = Catalog.of(java.util.List.of(new Catalog.Card("22222222-2222-2222-2222-222222222222", "K", "C", "K", 1,
                "pokemon", java.util.List.of(), java.util.List.of(), JSON.createObjectNode())));
        JsonNode rewards = s.clientPayload(catalog).get("tiers").get(0).get("rewards").get("5");
        assertEquals("card", rewards.get(0).get("name").asText());
        assertEquals("prod", rewards.get(1).get("name").asText());
        assertEquals("tok", rewards.get(2).get("name").asText());
        assertTrue(rewards.get(2).get("rewardProductID").isNull());
        assertEquals(2, rewards.get(2).get("index").asInt());
        assertEquals(10, s.clientPayload(catalog).get("startTime").asInt());
    }

    @Test
    void emptySeasonListGivesFallbackPayload() {
        JsonNode p = new Seasons(java.util.List.of(), () -> 0L).clientPayload(Catalog.empty());
        assertEquals("Season1", p.get("seasonID").asText());
    }
}
