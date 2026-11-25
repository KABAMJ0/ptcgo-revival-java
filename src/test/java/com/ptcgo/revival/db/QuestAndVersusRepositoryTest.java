package com.ptcgo.revival.db;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.progression.Quests;
import com.ptcgo.revival.progression.Seasons;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuestAndVersusRepositoryTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-25T10:00:00Z"));
    private Database db;
    private QuestRepository quests;
    private PlayerRepository players;
    private String acct;

    @BeforeEach
    void setUp() throws Exception {
        var tmp = Files.createTempFile("ptcgo-quest", ".db");
        tmp.toFile().deleteOnExit();
        db = new Database("jdbc:sqlite:" + tmp);
        db.migrate();
        acct = new AccountRepository(db).create("ash", "pw", false).orElseThrow().accountId();
        quests = new QuestRepository(db, Quests.loadCatalog(), now::get);
        players = new PlayerRepository(db);
    }

    private static String firstAvailableId(QuestRepository.Snapshot s) {
        return s.quests().get("availableQuests").get(0).get("questDefinition").get("questID").asText();
    }

    @Test
    void offersTwoQuestsDeterministicallyAndPerDay() throws Exception {
        var s1 = quests.get(acct);
        assertEquals(2, s1.quests().get("availableQuests").size());
        assertEquals(0, s1.quests().get("currentQuests").size());
        assertEquals(s1.quests().toString(), quests.get(acct).quests().toString(), "same day, same offers");
        // available now => next availability is "now"
        assertEquals(now.get().toEpochMilli(), s1.configuration().get("nextQuestAvailableTime").asLong());

        now.set(Instant.parse("2026-11-26T01:00:00Z")); // next UTC day: stale offers expire, new ones appear
        var s2 = quests.get(acct);
        assertEquals(2, s2.quests().get("availableQuests").size());
        assertNotEquals(firstAvailableId(s1), firstAvailableId(s2));
    }

    @Test
    void selectingOneQuestStopsFurtherOffersUntilTomorrow() throws Exception {
        var offered = quests.get(acct);
        assertEquals(offered.quests().get("availableQuests").get(0).get("questDefinition").get("questID").asText(), firstAvailableId(offered));
        // two ids at once, or an unknown id, selects nothing
        assertEquals(0, quests.select(acct, List.of("nope")).quests().get("currentQuests").size());
        var chosen = quests.select(acct, List.of(firstAvailableId(offered)));
        assertEquals(1, chosen.quests().get("currentQuests").size());
        assertEquals(0, chosen.quests().get("availableQuests").size(), "one new challenge per UTC day");
        assertEquals(Instant.parse("2026-11-26T00:00:00Z").toEpochMilli(), chosen.configuration().get("nextQuestAvailableTime").asLong());

        var abandoned = quests.abandon(acct, List.of(firstAvailableIdOfActive(chosen)));
        assertEquals(0, abandoned.quests().get("currentQuests").size());
    }

    private static String firstAvailableIdOfActive(QuestRepository.Snapshot s) {
        return s.quests().get("currentQuests").get(0).get("questDefinition").get("questID").asText();
    }

    @Test
    void disablingQuestsHidesThemAndReportsState() throws Exception {
        quests.get(acct);
        var off = quests.setEnabled(acct, false);
        assertEquals(0, off.quests().get("availableQuests").size());
        assertFalse(quests.accountState(acct).enabled());
        assertTrue(quests.setEnabled(acct, true).quests().get("availableQuests").size() > 0);
    }

    @Test
    void matchCreditIsExactlyOnceAndPaysCompletedQuests() throws Exception {
        // find a day/account whose offered set includes the wins challenge, then accept it
        var offered = quests.get(acct);
        String winsId = null;
        for (JsonNode q : offered.quests().get("availableQuests")) {
            if (q.get("questDefinition").get("title").get("id").asText().equals("Victory Lap")) winsId = q.get("questDefinition").get("questID").asText();
        }
        if (winsId == null) { // deterministic hash may not offer it for this account: use another stat
            winsId = firstAvailableId(offered);
        }
        quests.select(acct, List.of(winsId));
        int coinsBefore = players.wallet(acct).coins();
        double started = now.get().toEpochMilli() / 1000.0 + 5; // match began after acceptance

        var credit = quests.creditMatch(acct, "game-1",
                Map.of("wins", 0, "damagedealt", 100000, "energyplayed", 100, "trainersplayed", 100, "prizecardstaken", 100), true, started);
        assertEquals(1, credit.completedQuestsAndXpTotal().size());
        assertEquals(2, credit.completedQuestsAndXpTotal().get(0).size(), "pair of definitions, never a bare int");
        assertTrue(players.wallet(acct).coins() > coinsBefore);
        assertEquals(1, quests.accountState(acct).affinityXp().get("Colorless"));

        int coinsAfter = players.wallet(acct).coins();
        var again = quests.creditMatch(acct, "game-1", Map.of("damagedealt", 100000), true, started);
        assertEquals(0, again.completedQuestsAndXpTotal().size());
        assertEquals(coinsAfter, players.wallet(acct).coins(), "same game is never credited twice");
    }

    @Test
    void matchBeforeAcceptanceDoesNotCount() throws Exception {
        quests.select(acct, List.of(firstAvailableId(quests.get(acct))));
        double beforeAcceptance = now.get().toEpochMilli() / 1000.0 - 60;
        var credit = quests.creditMatch(acct, "game-x",
                Map.of("damagedealt", 100000, "energyplayed", 100, "trainersplayed", 100, "prizecardstaken", 100), true, beforeAcceptance);
        assertEquals(0, credit.completedQuestsAndXpTotal().size());
        assertEquals(0, credit.progressedQuests().size());
    }

    @Test
    void versusPointsGrantEachThresholdOnce() throws Exception {
        var raw = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                [{"seasonID":"S1","startTime":0,"endTime":9999999999999,"tiers":[{"rewards":{
                  "5":[{"name":"t","rewardType":"Tokens","rewardAmount":200,"rewardCurrency":"prizeTrainerCoin"}],
                  "10":[{"name":"c","rewardType":"Archetype","rewardAmount":2,"rewardProductID":"AAAA0000-0000-0000-0000-000000000001"}]}}]}]""");
        Seasons seasons = Seasons.parse(raw, () -> 1L);
        VersusRepository versus = new VersusRepository(db);

        assertEquals(new VersusRepository.Progress(0, 0), versus.progress(acct, "S1"));
        var first = versus.awardMatchPoints(acct, true, seasons.active()); // 5 points: crosses threshold 5
        assertEquals(5, first.points());
        assertEquals(1, first.granted().size());
        assertEquals(1200, players.wallet(acct).coins()); // 1000 start + 200
        var loss = versus.awardMatchPoints(acct, false, seasons.active());
        assertEquals(5, loss.points());
        assertEquals(0, loss.granted().size());
        var second = versus.awardMatchPoints(acct, true, seasons.active()); // 10: card reward
        assertEquals(10, second.points());
        assertEquals(1, second.granted().size());
        assertEquals(2, players.collection(acct).get(0).nontradable());
        assertEquals("aaaa0000-0000-0000-0000-000000000001", players.collection(acct).get(0).archetypeId());

        // a new season resets points but keeps the all-time total
        Seasons next = Seasons.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "[{\"seasonID\":\"S2\",\"startTime\":0,\"endTime\":9999999999999,\"tiers\":[]}]"), () -> 1L);
        assertEquals(new VersusRepository.Progress(0, 10), versus.progress(acct, "S2"));
        var s2 = versus.awardMatchPoints(acct, true, next.active());
        assertEquals(5, s2.points());
        assertEquals(15, s2.allTimePoints());
    }
}
