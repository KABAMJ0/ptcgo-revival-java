package com.ptcgo.revival.db;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.progression.DailyRewards;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DailyLoginRepositoryTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-25T10:00:00Z"));
    private DailyLoginRepository daily;
    private PlayerRepository players;
    private String acct;

    @BeforeEach
    void setUp() throws Exception {
        var tmp = Files.createTempFile("ptcgo-daily", ".db");
        tmp.toFile().deleteOnExit();
        Database db = new Database("jdbc:sqlite:" + tmp);
        db.migrate();
        acct = new AccountRepository(db).create("ash", "pw", false).orElseThrow().accountId();
        players = new PlayerRepository(db);
        players.wallet(acct); // login creates it
        Catalog catalog = Catalog.empty();
        daily = new DailyLoginRepository(db, DailyRewards.loadBundled(), catalog, now::get, new Random(1));
    }

    @Test
    void firstLoginPaysWelcomeTokensOnce() throws Exception {
        var first = daily.process(acct);
        assertTrue(first.firstDailyLogin());
        assertEquals(1, first.activations());
        assertEquals(1, first.rewardDay());
        assertEquals(1100, players.wallet(acct).coins()); // newbie day 1: 100 Welcome Tokens
        assertEquals(Instant.parse("2026-11-26T00:00:00Z").toEpochMilli(), first.nextRewardTimestampMs());

        now.set(Instant.parse("2026-11-25T23:59:00Z")); // same UTC day: nothing more
        var again = daily.process(acct);
        assertFalse(again.firstDailyLogin());
        assertEquals(1, again.activations());
        assertEquals(1100, players.wallet(acct).coins());
    }

    @Test
    void consecutiveDaysAdvanceTheTrackAndAMissedDayRestartsIt() throws Exception {
        daily.process(acct); // day 1 (activation 1)
        now.set(Instant.parse("2026-11-26T08:00:00Z"));
        var second = daily.process(acct); // newbie day 2: a random booster (no packs in an empty catalog: nothing granted)
        assertEquals(2, second.rewardDay());
        now.set(Instant.parse("2026-11-27T08:00:00Z"));
        var third = daily.process(acct); // newbie day 3: 1 event ticket
        assertEquals(3, third.rewardDay());
        assertEquals(101, players.wallet(acct).tickets());

        now.set(Instant.parse("2026-11-30T08:00:00Z")); // missed days: streak restarts at 1, but activations (4) leave the newbie track
        var after = daily.process(acct);
        assertEquals(4, after.activations());
        assertEquals(1, after.rewardDay());
        assertEquals(1150, players.wallet(acct).coins()); // standard day 1 = 50 tokens
        assertFalse(daily.rewards().isNewbie(after.activations()));
    }

    @Test
    void trackValidationRejectsBadConfigs() throws Exception {
        var m = new ObjectMapper();
        assertThrows(IllegalArgumentException.class, () -> DailyRewards.parse(m.readTree("{\"newbie\":[],\"standard\":[]}")));
        assertThrows(IllegalArgumentException.class, () -> DailyRewards.parse(m.readTree(
                "{\"newbie\":[[{\"rewardType\":\"Bogus\"}],[{\"rewardType\":\"Tokens\"}],[{\"rewardType\":\"Tokens\"}]],\"standard\":[[],[],[],[],[]]}")));
        var week = DailyRewards.loadBundled().weeksRewards(1);
        assertEquals(3, week.size(), "newbie dialog has 3 slots");
        assertEquals(5, DailyRewards.loadBundled().weeksRewards(4).size(), "standard dialog has 5 slots");
        assertEquals("Tokens", week.get(0).get(0).get("rewardType").asText());
        assertEquals(0, week.get(0).get(0).get("index").asInt());
    }
}
