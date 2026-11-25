package com.ptcgo.revival.packet;

import static com.ptcgo.revival.packet.TournamentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.TournamentRepository;
import com.ptcgo.revival.tournament.FakeTournamentMatchRunner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TournamentAsyncIntegrationTest {
    private static TournamentTestSupport env(boolean matchmaking) throws Exception {
        return new TournamentTestSupport(new FakeTournamentMatchRunner(FakeTournamentMatchRunner.Mode.MANUAL), matchmaking, null);
    }

    private static void assertError(SocialTestSupport.Client c, String name, String text, int rid) throws Exception {
        JsonNode v = c.expect(name);
        assertEquals(rid, c.lastRequestId);
        assertEquals(text, v.get("error").get("id").asText());
        assertEquals(1, v.size());
    }

    @Test
    void listJoinAndErrors() throws Exception {
        try (TournamentTestSupport env = env(false); SocialTestSupport.Client ash = env.login("Ash")) {
            env.seed("t-async", definition("Async Cup", 8, true, 50));
            String deck = env.deckFor(ash, "Main");

            ash.send("GetActiveAsyncTournaments", Map.of(), 10);
            JsonNode list = ash.expect("ActiveAsyncTournaments");
            assertEquals(10, ash.lastRequestId);
            assertEquals(1, list.get("tournamentDefinitions").size());
            JsonNode def = list.get("tournamentDefinitions").get(0);
            assertEquals("t-async", def.get("id").asText());
            assertEquals("Async Cup", def.get("title").get("id").asText());
            assertEquals("coins", def.get("run").get("entryFee").get(0).get("currency").asText());
            assertEquals("NoReward", def.get("run").get("prizeTable").get(0).get("rewards").get(0).get("name").asText());
            assertEquals(500, def.get("run").get("prizeTable").get(0).get("rewards").get(0).get("rewardAmount").asInt());
            assertEquals("SingleGame", def.get("game").get("matchStructure").asText());
            assertTrue(def.get("game").get("format").isNull());
            assertTrue(def.get("league").isNull());
            assertEquals(0, list.get("tournamentProgress").size());
            assertTrue(list.get("claimedLeaderboard").isObject());

            ash.send("JoinAsyncTournament", Map.of("tournamentID", "nope", "currency", "coins", "deckID", deck), 11);
            assertError(ash, "JoinAsyncTournamentError", "This tournament is not available.", 11);
            ash.send("JoinAsyncTournament", Map.of("tournamentID", "t-async", "currency", "coins", "deckID", "missing"), 12);
            assertError(ash, "JoinAsyncTournamentError", "The selected deck could not be found.", 12);
            ash.send("JoinAsyncTournament", Map.of("tournamentID", "t-async", "currency", "gems", "deckID", deck), 13);
            assertError(ash, "JoinAsyncTournamentError", "Invalid entry fee currency.", 13);
            env.wallet(ash, 10, 0, 0);
            ash.send("JoinAsyncTournament", Map.of("tournamentID", "t-async", "currency", "coins", "deckID", deck), 14);
            assertError(ash, "JoinAsyncTournamentError", "You cannot afford the entry fee.", 14);
            assertEquals(10, env.coins(ash));

            env.wallet(ash, 1000, 0, 0);
            ash.send("JoinAsyncTournament", Map.of("tournamentID", "T-ASYNC", "currency", "COINS", "deckID", deck.toUpperCase()), 15);
            JsonNode joined = ash.expect("AsyncTournamentJoined");
            assertEquals(15, ash.lastRequestId);
            assertEquals("t-async", joined.get("tournamentID").asText());
            String entryId = joined.get("entryID").asText();
            assertEquals(60, joined.get("deck").get("piles").get("deck").size());
            JsonNode progress = joined.get("progress");
            assertEquals("t-async", progress.get("tournamentDefID").asText());
            assertEquals(entryId, progress.get("entryID").asText());
            assertEquals(0, progress.get("wins").asInt());
            assertEquals(0, progress.get("tiebreakers").asInt());
            assertEquals(0, progress.get("limitedCollection").size());
            assertEquals(950, ash.expect("CurrentWallet").get("currencies").get(0).get("value").asInt());

            ash.send("JoinAsyncTournament", Map.of("tournamentID", "t-async", "currency", "coins", "deckID", deck), 16);
            assertError(ash, "JoinAsyncTournamentError", "You already have an active run in this tournament.", 16);

            ash.send("GetNumberOfPlayerRuns", Map.of("tournamentID", "t-async"), 17);
            JsonNode runs = ash.expect("AsyncTournamentNumberOfPlayerRuns");
            assertEquals(1, runs.get("runs").asInt());
            assertEquals("t-async", runs.get("tournamentID").asText());

            ash.send("GetActiveAsyncTournaments", Map.of(), 18);
            assertEquals(entryId, ash.expect("ActiveAsyncTournaments").get("tournamentProgress").get(0).get("entryID").asText());

            // Matchmaking not wired: the run exists but a game cannot start.
            ash.send("StartAsyncTournamentGame", Map.of("entryID", entryId), 19);
            assertError(ash, "StartAsyncTournamentGameError", "Tournament matches are not available on this server yet.", 19);
            ash.send("StartAsyncTournamentGame", Map.of("entryID", "nope"), 20);
            assertError(ash, "StartAsyncTournamentGameError", "Tournament run not found.", 20);

            // Not open: a tournament that closed for entries; and a disabled one.
            env.seed("t-closed", definition("Closed", 8, false, 0));
            ash.send("JoinAsyncTournament", Map.of("tournamentID", "t-closed", "currency", "", "deckID", deck), 21);
            assertError(ash, "JoinAsyncTournamentError", "This tournament is not open for entries.", 21);
            env.repo().upsertTournament(null, "t-closed", false);
            env.tournaments.reload();
            ash.send("JoinAsyncTournament", Map.of("tournamentID", "t-closed", "currency", "", "deckID", deck), 22);
            assertError(ash, "JoinAsyncTournamentError", "This tournament is not available.", 22);
        }
    }

    @Test
    void playRecordFinishAndClaim() throws Exception {
        try (TournamentTestSupport env = env(true);
             SocialTestSupport.Client ash = env.login("Ash"); SocialTestSupport.Client misty = env.login("Misty")) {
            int base = env.coins(ash); // login may already have paid a daily reward
            env.seed("t-async", definition("Async Cup", 8, true, 0));
            String ashDeck = env.deckFor(ash, "Main");
            String mistyDeck = env.deckFor(misty, "Water");
            ash.send("JoinAsyncTournament", Map.of("tournamentID", "t-async", "currency", "", "deckID", ashDeck), 1);
            String ashEntry = ash.expect("AsyncTournamentJoined").get("entryID").asText();
            ash.expect("CurrentWallet");
            misty.send("JoinAsyncTournament", Map.of("tournamentID", "t-async", "currency", "", "deckID", mistyDeck), 1);
            String mistyEntry = misty.expect("AsyncTournamentJoined").get("entryID").asText();
            misty.expect("CurrentWallet");

            // Start with a deck switch: DeckUpdated (rid 0), then the empty ack on the request id, then the queue push.
            String newDeck = env.deckFor(ash, "Side");
            ash.send("StartAsyncTournamentGame", Map.of("entryID", ashEntry, "deckID", newDeck), 30);
            JsonNode upd = ash.expect("AsyncTournamentDeckUpdated");
            assertEquals(0, ash.lastRequestId);
            assertEquals("Side", upd.get("deck").get("deckName").asText());
            assertEquals(ashEntry, upd.get("entryID").asText());
            JsonNode ack = ash.next();
            assertEquals(30, ash.lastRequestId);
            assertEquals(0, ack.size() == 0 ? 0 : ack.path("value").size());
            assertEquals("MatchQueueEntered", ash.next().get("name").asText());
            assertEquals(List.of("AsyncTournament_t-async", "t-async", ashEntry), List.of(env.enqueued.get(0)));
            assertEquals("Side", env.repo().getEntry(ashEntry).deck().get("deckName").asText());

            // The engine reports game one: Ash wins.
            Map<String, String> entries = new java.util.LinkedHashMap<>(Map.of(ash.accountId, ashEntry));
            entries.put(misty.accountId, mistyEntry);
            Map<String, com.ptcgo.revival.packet.ClientContext> players = new java.util.HashMap<>();
            players.put(ash.accountId, env.services.registry().find(ash.accountId).orElseThrow());
            players.put(misty.accountId, env.services.registry().find(misty.accountId).orElseThrow());
            Map<String, String> names = Map.of(ash.accountId, "Ash", misty.accountId, "Misty");
            env.tournaments.recordAsyncGame("t-async", entries, ash.accountId, players, names);
            JsonNode p1 = ash.expect("AsyncTournamentProgressUpdated");
            assertEquals(0, ash.lastRequestId);
            assertEquals(1, p1.get("wins").asInt());
            assertEquals(0, p1.get("losses").asInt());
            assertEquals(0, p1.get("rank").asInt());
            assertEquals(ashEntry, p1.get("entryID").asText());
            JsonNode m1 = misty.expect("AsyncTournamentProgressUpdated");
            assertEquals(1, m1.get("losses").asInt());

            // Game two: Ash reaches maxWins (2): progress, then the run's prize (row 2..2 = 100 tokens).
            env.tournaments.recordAsyncGame("t-async", entries, ash.accountId, players, names);
            assertEquals(2, ash.expect("AsyncTournamentProgressUpdated").get("wins").asInt());
            JsonNode rewards = ash.expect("AsyncTournamentRewards");
            assertEquals(2, rewards.get("wins").asInt());
            JsonNode r0 = rewards.get("rewards").get(0);
            assertEquals("NoReward", r0.get("name").asText());
            assertEquals("Tokens", r0.get("rewardType").asText());
            assertEquals(100, r0.get("rewardAmount").asInt());
            assertEquals(base + 100, env.coins(ash));
            // Misty hit maxLosses (2) at the same time.
            assertEquals(2, misty.expect("AsyncTournamentProgressUpdated").get("losses").asInt());
            assertEquals(0, misty.expect("AsyncTournamentRewards").get("rewards").size());

            // History and the already-complete run.
            ash.send("GetAsyncTournamentGameHistory", Map.of("tournamentID", "t-async", "entryID", ashEntry), 31);
            JsonNode hist = ash.expect("AsyncTournamentGameHistoryList");
            assertEquals(2, hist.get("games").size());
            assertEquals("Win", hist.get("games").get(0).get("gameResult").asText());
            assertEquals("Misty", hist.get("games").get(0).get("opponentName").asText());
            misty.send("GetAsyncTournamentGameHistory", Map.of("tournamentID", "t-async", "entryID", ashEntry), 32);
            assertEquals(0, misty.expect("AsyncTournamentGameHistoryList").get("games").size()); // someone else's run

            ash.send("StartAsyncTournamentGame", Map.of("entryID", ashEntry), 33);
            assertError(ash, "StartAsyncTournamentGameError", "This tournament run is already complete.", 33);

            // Claiming after the automatic finish grants nothing again (rewards_claimed).
            ash.send("ClaimAsyncTournamentReward", Map.of("entryID", ashEntry), 34);
            JsonNode again = ash.expect("AsyncTournamentRewards");
            assertEquals(34, ash.lastRequestId);
            assertEquals(0, again.get("rewards").size());
            assertEquals(base + 100, env.coins(ash));
            assertEquals("CurrentWallet", ash.next().get("name").asText());
            ash.send("ClaimAsyncTournamentReward", Map.of("entryID", "nope"), 35);
            assertError(ash, "ClaimAsyncTournamentRewardError", "Tournament run not found.", 35);

            // The run is over, so a fresh one is allowed (maxRuns 2), then a third is not.
            ash.send("JoinAsyncTournament", Map.of("tournamentID", "t-async", "currency", "", "deckID", ashDeck), 36);
            ash.expect("AsyncTournamentJoined");
            ash.expect("CurrentWallet");
            ash.send("ResignTournamentRun", Map.of("entryID", ash.accountId + "-x"), 37); // unknown: silently ignored
            String second = env.repo().activeEntries(ash.accountId).get(0).entryId();
            ash.send("ResignTournamentRun", Map.of("entryID", second), 38);
            JsonNode resigned = ash.expect("AsyncTournamentRewards");
            assertEquals(38, ash.lastRequestId);
            assertEquals(0, resigned.get("wins").asInt());
            assertEquals(0, resigned.get("rewards").size());
            ash.expect("CurrentWallet");
            assertEquals("resigned", env.repo().getEntry(second).status());
            ash.send("JoinAsyncTournament", Map.of("tournamentID", "t-async", "currency", "", "deckID", ashDeck), 39);
            assertError(ash, "JoinAsyncTournamentError", "No runs remaining for this tournament.", 39);
        }
    }

    @Test
    void leaderboardStandingsAndClaims() throws Exception {
        try (TournamentTestSupport env = env(false);
             SocialTestSupport.Client ash = env.login("Ash"); SocialTestSupport.Client misty = env.login("Misty");
             SocialTestSupport.Client brock = env.login("Brock")) {
            int base = env.coins(ash);
            env.seed("t-done", definition("Finished Cup", 8, false, 0));
            env.seed("t-open", definition("Open Cup", 8, true, 0));
            TournamentRepository repo = env.repo();
            String ashEntry = repo.createEntry(ash.accountId, "t-done", repo.listTournaments(false).get(0).definition(), "", TournamentTestSupport.JSON.createObjectNode()).entry().entryId();
            String mistyEntry = repo.createEntry(misty.accountId, "t-done", repo.listTournaments(false).get(0).definition(), "", TournamentTestSupport.JSON.createObjectNode()).entry().entryId();
            JsonNode run = TournamentTestSupport.JSON.createObjectNode();
            repo.recordGameResult(ashEntry, true, misty.accountId, "Misty", run);
            repo.recordGameResult(ashEntry, true, misty.accountId, "Misty", run);
            repo.recordGameResult(mistyEntry, true, ash.accountId, "Ash", run);
            repo.recordGameResult(mistyEntry, false, ash.accountId, "Ash", run);

            ash.send("GetAsyncTournamentStandings", Map.of("tournamentID", "t-done"), 1);
            JsonNode st = ash.expect("AsyncTournamentStandings").get("standings");
            assertEquals(2, st.size());
            assertEquals(ash.accountId, st.get(0).get("accountID").asText());
            assertEquals("Ash", st.get(0).get("displayName").asText());
            assertEquals(6.0, st.get(0).get("points").asDouble());
            assertTrue(st.get(0).get("points").isDouble());
            assertEquals(1, st.get(0).get("rank").asInt());
            assertEquals("1", st.get(0).get("displayRank").asText());
            assertEquals(3.0, st.get(1).get("points").asDouble());

            misty.send("GetTopRankedStandings", Map.of("tournamentID", "t-done", "count", 1), 2);
            JsonNode top = misty.expect("AsyncTournamentLeaderboardTopRankedStandings");
            assertEquals(1, top.get("standings").size());
            assertEquals(2, top.get("playerRank").asInt());
            assertEquals(misty.accountId, top.get("playerStanding").get("accountID").asText());
            brock.send("GetTopRankedStandings", Map.of("tournamentID", "t-done"), 3);
            JsonNode topB = brock.expect("AsyncTournamentLeaderboardTopRankedStandings");
            assertEquals(2, topB.get("standings").size());
            assertEquals(0, topB.get("playerRank").asInt());
            assertTrue(topB.get("playerStanding").isNull());

            misty.send("GetPlayerRankAndSurroundingStandings", Map.of("tournamentID", "t-done", "above", 1, "below", 0), 4);
            JsonNode map = misty.expect("AsyncTournamentLeaderboardPlayerRankAndSurroundingStandings").get("standingMap");
            assertEquals(2, map.size());
            assertEquals(ash.accountId, map.get("1").get("accountID").asText());
            assertEquals(misty.accountId, map.get("2").get("accountID").asText());
            brock.send("GetPlayerRankAndSurroundingStandings", Map.of("tournamentID", "t-done", "above", 3, "below", 3), 5);
            assertEquals(0, brock.expect("AsyncTournamentLeaderboardPlayerRankAndSurroundingStandings").get("standingMap").size());

            // CanClaim: resolved + participated; not participated; still running.
            ash.send("GetCanClaimLeaderboardRewardsForTournament", Map.of("tournamentID", "t-done"), 6);
            JsonNode can = ash.expect("CanClaimLeaderboardRewardsForTournament");
            assertTrue(can.get("hasParticipated").asBoolean());
            assertTrue(can.get("claimAvailable").asBoolean());
            assertEquals(1, can.get("rank").asInt());
            assertEquals(0, can.get("availableIn").asInt());
            brock.send("GetCanClaimLeaderboardRewardsForTournament", Map.of("tournamentID", "t-done"), 7);
            JsonNode canB = brock.expect("CanClaimLeaderboardRewardsForTournament");
            assertFalse(canB.get("hasParticipated").asBoolean());
            assertFalse(canB.get("claimAvailable").asBoolean());
            assertTrue(canB.get("rank").isNull());
            ash.send("GetCanClaimLeaderboardRewardsForTournament", Map.of("tournamentID", "t-open"), 8);
            JsonNode canO = ash.expect("CanClaimLeaderboardRewardsForTournament");
            assertFalse(canO.get("claimAvailable").asBoolean());
            assertTrue(canO.get("availableIn").asLong() > 0);

            ash.send("HasClaimedAsyncTournamentLeaderboardReward", Map.of("tournamentID", "t-done"), 9);
            assertFalse(ash.expect("AsyncTournamentHasClaimedLeaderboardReward").get("claimed").asBoolean());

            ash.send("ClaimAsyncTournamentLeaderboardReward", Map.of("tournamentID", "t-open"), 10);
            assertError(ash, "ClaimAsyncTournamentRewardError", "Leaderboard rewards are not available yet.", 10);
            brock.send("ClaimAsyncTournamentLeaderboardReward", Map.of("tournamentID", "t-done"), 11);
            assertError(brock, "ClaimAsyncTournamentRewardError", "You did not participate in this tournament.", 11);

            ash.send("ClaimAsyncTournamentLeaderboardReward", Map.of("tournamentID", "t-done"), 12);
            JsonNode claim = ash.expect("AsyncTournamentLeaderboardRewards");
            assertEquals(12, ash.lastRequestId);
            assertEquals(1, claim.get("rank").asInt());
            assertEquals(1000, claim.get("rewards").get(0).get("rewardAmount").asInt());
            assertEquals(base + 1000, ash.expect("CurrentWallet").get("currencies").get(0).get("value").asInt());
            ash.send("ClaimAsyncTournamentLeaderboardReward", Map.of("tournamentID", "t-done"), 13);
            assertError(ash, "ClaimAsyncTournamentRewardError", "Leaderboard rewards already claimed.", 13);
            ash.send("HasClaimedAsyncTournamentLeaderboardReward", Map.of("tournamentID", "t-done"), 14);
            assertTrue(ash.expect("AsyncTournamentHasClaimedLeaderboardReward").get("claimed").asBoolean());
            ash.send("GetActiveAsyncTournaments", Map.of(), 15);
            JsonNode listed = ash.expect("ActiveAsyncTournaments");
            assertTrue(listed.get("claimedLeaderboard").get("t-done").asBoolean());
            assertEquals(2, listed.get("tournamentDefinitions").size());

            // Misty is rank 2: row 2..5 pays 200.
            misty.send("ClaimAsyncTournamentLeaderboardReward", Map.of("tournamentID", "t-done"), 16);
            assertEquals(200, misty.expect("AsyncTournamentLeaderboardRewards").get("rewards").get(0).get("rewardAmount").asInt());

            // Pack / league stubs.
            ash.send("GetUnacknowledgedPacks", Map.of("entryID", "e1"), 17);
            JsonNode packs = ash.expect("AsyncTournamentUnacknowledgedPacks");
            assertEquals("e1", packs.get("tournamentEntryID").asText());
            assertEquals(0, packs.get("packs").size());
            assertTrue(packs.get("additionalRewards").isNull());
            ash.send("AcknowledgePack", Map.of("entryID", "e1", "packID", "p1"), 18);
            assertEquals("p1", ash.expect("PackAcknowledged").get("packID").asText());
            ash.send("GetLeagueTiebreakersRemaining", Map.of("tournamentID", "t1", "entryID", "e1"), 19);
            JsonNode lt = ash.expect("LeagueTiebreakersRemaining");
            assertEquals(0, lt.get("tiebreakersRemaining").asInt());
            assertEquals(0, lt.get("week").asInt());
        }
    }
}
