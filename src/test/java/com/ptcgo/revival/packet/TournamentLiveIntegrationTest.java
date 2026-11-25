package com.ptcgo.revival.packet;

import static com.ptcgo.revival.packet.TournamentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.tournament.FakeTournamentMatchRunner;
import com.ptcgo.revival.tournament.FakeTournamentMatchRunner.Mode;
import com.ptcgo.revival.tournament.LiveTournament;
import com.ptcgo.revival.tournament.TournamentMatchRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TournamentLiveIntegrationTest {
    private static final String ZERO = "00000000-0000-0000-0000-000000000000";

    private static void join(TournamentTestSupport env, SocialTestSupport.Client c, String tid, String deck, int rid) throws Exception {
        c.send("JoinTournament", Map.of("tournamentID", tid, "deck", deck), rid);
    }

    /** Logs in a player with a saved deck and exactly 1000 coins. */
    private static SocialTestSupport.Client player(TournamentTestSupport env, String name, String[] deckOut) throws Exception {
        SocialTestSupport.Client c = env.login(name);
        env.wallet(c, 1000, 0, 100);
        deckOut[0] = env.deckFor(c, name + " deck");
        return c;
    }

    private static void awaitOffline(TournamentTestSupport env, String accountId) throws Exception {
        for (int i = 0; i < 100 && env.services.registry().find(accountId).isPresent(); i++) Thread.sleep(50);
        assertTrue(env.services.registry().find(accountId).isEmpty(), "client still registered");
    }

    @Test
    void listingQueueAndLobbyLifecycle() throws Exception {
        try (TournamentTestSupport env = new TournamentTestSupport(new FakeTournamentMatchRunner(Mode.MANUAL), false, null)) {
            env.seed("t-live", definition("Live Cup", 4, true, 100));
            env.seed("t-mod", definitionWithFormat("Modern Cup", "Modified"));
            env.seed("t-shut", definition("Shut Cup", 4, false, 0));
            String[] d1 = new String[1], d2 = new String[1];
            try (SocialTestSupport.Client ash = player(env, "Ash", d1); SocialTestSupport.Client misty = player(env, "Misty", d2)) {
                ash.send("GetActiveTournaments", Map.of(), 1);
                JsonNode list = ash.expect("AvailableTournamentList");
                assertEquals(1, ash.lastRequestId);
                assertEquals(3, list.get("tournamentList").size());
                JsonNode live = list.get("tournamentList").get(0);
                assertEquals("t-live", live.get("tournamentID").asText());
                assertEquals("Live Cup", live.get("name").asText());
                assertTrue(live.get("il8nName").isNull());
                assertEquals(4, live.get("maxSize").asInt());
                assertEquals("SingleElimination", live.get("matchStructure").asText());
                assertEquals("Tokens", live.get("entryFee").get(0).get("feeType").asText());
                assertEquals(100, live.get("entryFee").get(0).get("feeAmount").asInt());
                assertEquals("Tokens", live.get("prizes").get(0).get("prizeType").get("type").asText());
                assertTrue(live.get("prizes").get(0).get("prizeType").get("archetypeID").isNull());
                assertEquals(1, live.get("prizes").get(0).get("startPlace").asInt());
                assertTrue(live.get("active").asBoolean());
                assertFalse(list.get("tournamentList").get(2).get("active").asBoolean()); // entry closed
                assertEquals(0, list.get("tournamentQueues").get("t-live").asInt());

                misty.send("SubscribeToTournamentsChannel", Map.of(), 2);
                JsonNode sub = misty.expect("SubscribeToTournamentChannelSuccessful");
                assertEquals(0, sub.get("tournamentQueues").get("t-live").asInt());

                // Errors that cost nothing.
                join(env, ash, "nope", d1[0], 3);
                assertEquals("That tournament is not open for registration.", ash.expect("JoinTournamentFailed").get("reason").get("id").asText());
                join(env, ash, "t-shut", d1[0], 4);
                assertEquals("That tournament is not open for registration.", ash.expect("JoinTournamentFailed").get("reason").get("id").asText());
                join(env, ash, "t-live", "no-such-deck", 5);
                assertEquals("The selected deck could not be found.", ash.expect("JoinTournamentFailed").get("reason").get("id").asText());
                join(env, ash, "t-mod", d1[0], 6); // the placeholder validator only knows Unlimited
                JsonNode invalid = ash.expect("JoinTournamentFailedInvalidDeck");
                assertFalse(invalid.get("deckValidationResult").get(0).get("valid").asBoolean());
                assertEquals(1000, env.coins(ash));

                // Join: QueueJoined on the request id, wallet debit, status + roster pushes.
                join(env, ash, "T-LIVE", d1[0], 7);
                JsonNode queued = ash.expect("TournamentQueueJoined");
                assertEquals(7, ash.lastRequestId);
                assertEquals("t-live", queued.get("tournamentID").asText());
                assertEquals(900, ash.expect("CurrentWallet").get("currencies").get(0).get("value").asInt());
                JsonNode status = ash.expect("TournamentQueueStatus");
                assertEquals("t-live", status.get("tournamentID").asText());
                assertEquals(1, status.get("size").asInt());
                assertEquals(List.of("Ash"), textList(ash.expect("UsersInTournamentQueue").get("usernames")));
                assertEquals(1, misty.expect("TournamentQueueStatus").get("size").asInt()); // subscriber

                join(env, ash, "t-live", d1[0], 8);
                assertEquals("You are already in a tournament.", ash.expect("JoinTournamentFailed").get("reason").get("id").asText());

                misty.send("GetPlayersInQueue", Map.of("tournamentID", "t-live"), 9);
                assertEquals(List.of("Ash"), textList(misty.expect("UsersInTournamentQueue").get("usernames")));
                assertEquals(9, misty.lastRequestId);
                misty.send("GetActiveTournaments", Map.of(), 10);
                assertEquals(1, misty.expect("AvailableTournamentList").get("tournamentQueues").get("t-live").asInt());

                // Leave: refund, status 0 for the subscriber, then leaving again fails.
                ash.send("LeaveTournamentQueue", Map.of("tournamentID", "t-live"), 11);
                // As in Python the refund pushes (wallet, status, roster) come before the reply.
                JsonNode left = until(ash, "TournamentQueueLeft");
                assertEquals(11, ash.lastRequestId);
                assertEquals("t-live", left.get("tournamentID").asText());
                assertEquals(1000, env.coins(ash));
                assertEquals(0, misty.expect("TournamentQueueStatus").get("size").asInt());
                ash.send("LeaveTournamentQueue", Map.of("tournamentID", "t-live"), 12);
                drainUntil(ash, "TournamentQueueLeftFailed");

                // Cannot afford: WalletFailed, followed by a wallet resync.
                env.wallet(ash, 40, 0, 0);
                join(env, ash, "t-live", d1[0], 13);
                assertEquals("event.join.error.WalletFailed", ash.expect("JoinTournamentFailed").get("reason").get("id").asText());
                assertEquals(13, ash.lastRequestId);
                assertEquals(40, ash.expect("CurrentWallet").get("currencies").get(0).get("value").asInt());

                // Unsubscribe: no more status pushes to Misty (she joins nothing, Ash queues).
                misty.send("UnSubscribeToTournamentsChannel", Map.of(), 14);
                env.wallet(ash, 1000, 0, 0);
                join(env, ash, "t-live", d1[0], 15);
                drain(ash);
                assertTrue(misty.quiet());
            }
        }
    }

    @Test
    void disconnectLeavesQueueWithRefundAndChannel() throws Exception {
        try (TournamentTestSupport env = new TournamentTestSupport(new FakeTournamentMatchRunner(Mode.MANUAL), false, null)) {
            env.seed("t-live", definition("Live Cup", 4, true, 100));
            String[] d1 = new String[1], d2 = new String[1];
            try (SocialTestSupport.Client ash = player(env, "Ash", d1); SocialTestSupport.Client misty = player(env, "Misty", d2)) {
                misty.send("SubscribeToTournamentsChannel", Map.of(), 1);
                misty.expect("SubscribeToTournamentChannelSuccessful");
                join(env, ash, "t-live", d1[0], 2);
                drain(ash);
                assertEquals(1, misty.expect("TournamentQueueStatus").get("size").asInt());
                assertEquals(900, env.coins(ash));

                ash.send("HookDisconnectNow", Map.of(), 3);
                JsonNode status = misty.expect("TournamentQueueStatus");
                assertEquals(0, status.get("size").asInt());
                assertEquals(1000, env.coins(ash)); // refunded
                assertEquals(0, env.tournaments.live().queueSize("t-live"));
                assertNull(env.tournaments.live().findQueued(ash.accountId));

                // The asynchronous production hook does the same on a virtual thread.
                join(env, ash, "t-live", d1[0], 4);
                drain(ash);
                assertEquals(1, misty.expect("TournamentQueueStatus").get("size").asInt());
                ash.send("HookDisconnectAsync", Map.of(), 5);
                assertEquals(0, misty.expect("TournamentQueueStatus").get("size").asInt());
                assertEquals(1000, env.coins(ash));

                // A disconnecting subscriber leaves the Events channel.
                assertTrue(env.tournaments.live().isSubscribed(env.services.registry().find(misty.accountId).orElseThrow()));
                misty.send("HookDisconnectNow", Map.of(), 6);
                drain(misty);
                join(env, ash, "t-live", d1[0], 7);
                drain(ash);
                assertTrue(misty.quiet());
            }
        }
    }

    @Test
    void twoPlayerTournamentRunsThroughFakeRunner() throws Exception {
        FakeTournamentMatchRunner runner = new FakeTournamentMatchRunner(Mode.AUTO);
        try (TournamentTestSupport env = new TournamentTestSupport(runner, false, null)) {
            env.seed("t-two", definition("Duel", 2, true, 100));
            String[] d1 = new String[1], d2 = new String[1];
            try (SocialTestSupport.Client ash = player(env, "Ash", d1); SocialTestSupport.Client misty = player(env, "Misty", d2)) {
                join(env, ash, "t-two", d1[0], 1);
                drain(ash);
                join(env, misty, "t-two", d2[0], 1);
                List<JsonNode> m = drain(misty);
                List<JsonNode> a = drain(ash);

                assertEquals(1, runner.requests.size());
                TournamentMatchRunner.MatchRequest req = runner.requests.get(0);
                assertEquals("Tournament_t-two", req.queueName());
                assertEquals("t-two", req.tournamentId());
                assertEquals(2, req.seats().size());
                assertNotNull(req.seats().get(0).client());
                assertEquals(60, req.seats().get(0).deck().get("piles").get("deck").size());
                String winner = req.seats().get(0).accountId();
                SocialTestSupport.Client winC = winner.equals(ash.accountId) ? ash : misty;
                SocialTestSupport.Client loseC = winC == ash ? misty : ash;

                for (List<JsonNode> msgs : List.of(a, m)) {
                    JsonNode started = first(msgs, "TournamentStarted");
                    assertEquals(2, started.get("size").asInt());
                    List<JsonNode> updates = all(msgs, "TournamentRoundUpdated");
                    assertEquals(2, updates.size()); // round start, then the result
                    JsonNode round = updates.get(0).get("tournamentData");
                    assertEquals(1, round.get("currentRound").asInt());
                    assertEquals(1, round.get("matchups").size());
                    assertEquals(2, round.get("matchups").get(0).get("players").size());
                    assertTrue(round.get("matchups").get(0).get("winner").isNull());
                    assertEquals(30, round.get("roundLength").asInt());
                    assertEquals(winner, updates.get(1).get("tournamentData").get("matchups").get(0).get("winner").get("accountID").asText());
                    assertEquals(1, all(msgs, "TournamentCompleted").size());
                }
                JsonNode doneW = first(winC == ash ? a : m, "TournamentCompleted");
                JsonNode doneL = first(loseC == ash ? a : m, "TournamentCompleted");
                assertEquals(winner, doneW.get("finalStandings").get(0).get("accountID").asText());
                assertEquals(2, doneW.get("finalStandings").size());
                assertEquals(1, doneW.get("tournamentData").get("currentRound").asInt());
                assertEquals(1, doneW.get("prizes").size());
                assertEquals(500, doneW.get("prizes").get(0).get("rewardAmount").asInt());
                assertTrue(doneW.get("prizes").get(0).get("rewardProductID").isNull());
                assertEquals("coins", doneW.get("prizes").get(0).get("rewardCurrency").asText());
                assertEquals(0, doneW.get("prizes").get(0).get("index").asInt());
                assertEquals(100, doneL.get("prizes").get(0).get("rewardAmount").asInt());
                assertEquals(1000 - 100 + 500, env.coins(winC));
                assertEquals(1000 - 100 + 100, env.coins(loseC));
                // Prize holders got a wallet push after the final standings.
                assertFalse(all(winC == ash ? a : m, "CurrentWallet").isEmpty());

                // The bracket is gone; both players are free again.
                assertEquals(0, env.tournaments.live().activeCount());
                ash.send("IsUserInActiveTournament", Map.of(), 2);
                assertEquals(ZERO, ash.expect("UserInActiveTournament").get("activeTournamentID").asText());
                ash.send("GetTournamentInProgress", Map.of("activeTournamentID", "x"), 3);
                assertEquals(0, ash.expect("TournamentsInProgressData").get("tournamentData").size());
            }
        }
    }

    @Test
    void fourPlayerBracketWithRoundsCountdownAndNudges() throws Exception {
        FakeTournamentMatchRunner runner = new FakeTournamentMatchRunner(Mode.MANUAL);
        try (TournamentTestSupport env = new TournamentTestSupport(runner, false, null)) {
            env.seed("t4", definition("Cup of Four", 4, true, 100));
            String[] d = new String[1];
            List<SocialTestSupport.Client> cs = new ArrayList<>();
            try {
                for (String n : List.of("A", "B", "C", "D")) cs.add(player(env, n, d));
                // One deck per player is enough: join with each one's own deck.
                for (SocialTestSupport.Client c : cs) {
                    String deck = env.services.players().decks(c.accountId, false).get(0).id();
                    join(env, c, "t4", deck, 1);
                    drain(c);
                }
                for (SocialTestSupport.Client c : cs) drain(c); // pushes caused by the other joins
                // The 4th join started the bracket; everybody got the start and the round-1 table.
                assertEquals(1, env.tournaments.live().activeCount());
                LiveTournament t = env.tournaments.live().activeTournaments().get(0);
                assertEquals(1, t.currentRound());
                assertEquals(2, t.matchups().size());
                assertEquals(2, runner.pendingRequests().size());
                for (TournamentMatchRunner.MatchRequest r : runner.requests) {
                    assertEquals("Tournament_t4", r.queueName());
                    assertEquals(t.activeId(), r.activeId());
                }

                // Reconnect helpers: where am I?
                SocialTestSupport.Client c0 = cs.get(0);
                c0.send("IsUserInActiveTournament", Map.of(), 2);
                assertEquals(t.activeId(), c0.expect("UserInActiveTournament").get("activeTournamentID").asText());
                c0.send("GetActiveTournament", Map.of(), 3);
                assertEquals(t.activeId(), c0.expect("UserInActiveTournament").get("activeTournamentID").asText());
                c0.send("GetTournamentInProgress", Map.of("activeTournamentID", t.activeId()), 4);
                JsonNode prog = c0.expect("TournamentsInProgressData").get("tournamentData").get(0);
                assertEquals(1, prog.get("currentRound").asInt());
                assertEquals(2, prog.get("matchups").size());
                assertEquals(0, prog.get("matchups").get(0).get("table").asInt());
                // ... also by a stale active id: falls back to the player's own tournament.
                c0.send("GetTournamentInProgress", Map.of("activeTournamentID", "stale"), 5);
                assertEquals(t.activeId(), c0.expect("TournamentsInProgressData").get("tournamentData").get(0).get("activeTournamentID").asText());

                // Round 1: table 0 plays out (seat 0 wins), table 1 is conceded by seat 0.
                TournamentMatchRunner.MatchRequest g0 = runner.requests.get(0), g1 = runner.requests.get(1);
                String w0 = g0.seats().get(0).accountId();
                String w1 = g1.seats().get(1).accountId();
                runner.finish(g0.gameId(), w0, TournamentMatchRunner.Outcome.COMPLETED);
                assertEquals(1, env.tournaments.live().activeCount());
                assertTrue(env.scheduler.delays().contains(1L)); // nudge after a played game
                int before = env.scheduler.size();
                runner.finish(g1.gameId(), w1, TournamentMatchRunner.Outcome.CONCEDED);
                // Round over: countdown max(min 3, delayBetweenRounds 5) = 5 s.
                assertTrue(env.scheduler.delays().contains(5000L), env.scheduler.delays().toString());
                assertTrue(env.scheduler.size() > before);
                SocialTestSupport.Client any = cs.get(0);
                List<JsonNode> got = drain(any);
                JsonNode next = first(got, "TournamentNextRoundStarting");
                assertEquals(t.activeId(), next.get("activeTournamentID").asText());
                assertEquals(5, next.get("countdown").asInt());
                assertFalse(next.get("suppressPopup").asBoolean());
                for (SocialTestSupport.Client c : cs.subList(1, 4)) drain(c);

                // The scheduled tasks fire: nudges to the played match's players, then round 2 with the two winners.
                env.scheduler.runAll();
                assertEquals(2, t.currentRound());
                assertEquals(3, t.matchups().size());
                LiveTournament.Matchup fin = t.matchups().get(2);
                assertEquals(2, fin.round());
                assertEquals(0, fin.table());
                assertEquals(List.of(w0, w1), fin.players().stream().map(p -> p.accountId).toList());
                assertEquals(3, runner.requests.size());
                assertEquals(1, runner.pendingRequests().size());
                SocialTestSupport.Client w0c = cs.stream().filter(c -> c.accountId.equals(w0)).findFirst().orElseThrow();
                List<JsonNode> w0msgs = drain(w0c);
                assertTrue(names(w0msgs).contains("UserInActiveTournament"));
                assertEquals(2, first(w0msgs, "TournamentRoundUpdated").get("tournamentData").get("currentRound").asInt());
                // Nudges chain: the 2nd and 3rd are scheduled by the first.
                assertFalse(env.scheduler.delays().isEmpty());
                env.scheduler.clear();

                // Final: seat 1 (w1) beats w0.
                TournamentMatchRunner.MatchRequest finalReq = runner.requests.get(2);
                assertEquals(w0, finalReq.seats().get(0).accountId());
                runner.finish(finalReq.gameId(), w1, TournamentMatchRunner.Outcome.COMPLETED);
                assertEquals(0, env.tournaments.live().activeCount());
                SocialTestSupport.Client champ = cs.stream().filter(c -> c.accountId.equals(w1)).findFirst().orElseThrow();
                List<JsonNode> cm = drain(champ);
                JsonNode done = first(cm, "TournamentCompleted");
                JsonNode standings = done.get("finalStandings");
                assertEquals(4, standings.size());
                assertEquals(w1, standings.get(0).get("accountID").asText());
                assertEquals(w0, standings.get(1).get("accountID").asText());
                assertEquals(500, done.get("prizes").get(0).get("rewardAmount").asInt());
                assertEquals(3, done.get("tournamentData").get("matchups").size());
                assertEquals(1000 - 100 + 500, env.coins(champ));
                // Round-1 losers: nothing back (place 3 and 4 have no prize row).
                for (SocialTestSupport.Client c : cs) {
                    if (c.accountId.equals(w0) || c.accountId.equals(w1)) continue;
                    assertEquals(900, env.coins(c));
                    List<JsonNode> lm = drain(c);
                    assertEquals(0, first(lm, "TournamentCompleted").get("prizes").size());
                }
                assertEquals(1000 - 100 + 100, env.coins(w0c));
                // Nothing scheduled any more after completion fires harmlessly.
                env.scheduler.runAll();
            } finally {
                for (SocialTestSupport.Client c : cs) c.close();
            }
        }
    }

    @Test
    void leavingTheActiveTournamentForfeitsTheMatch() throws Exception {
        FakeTournamentMatchRunner runner = new FakeTournamentMatchRunner(Mode.MANUAL);
        try (TournamentTestSupport env = new TournamentTestSupport(runner, false, null)) {
            env.seed("t-two", definition("Duel", 2, true, 100));
            String[] d1 = new String[1], d2 = new String[1];
            try (SocialTestSupport.Client ash = player(env, "Ash", d1); SocialTestSupport.Client misty = player(env, "Misty", d2)) {
                join(env, ash, "t-two", d1[0], 1);
                drain(ash);
                join(env, misty, "t-two", d2[0], 1);
                drain(misty);
                drain(ash);
                LiveTournament t = env.tournaments.live().activeTournaments().get(0);
                TournamentMatchRunner.MatchRequest req = runner.requests.get(0);

                ash.send("LeaveActiveTournament", Map.of("activeTournamentID", t.activeId().toUpperCase()), 9);
                List<JsonNode> a = drain(ash);
                JsonNode left = first(a, "TournamentLeft");
                assertEquals(t.activeId(), left.get("activeTournamentID").asText());
                assertEquals(List.of(req.gameId()), runner.aborted);
                // Ash forfeited: Misty is champion, Ash (withdrawn) gets no prize but still sees the result.
                JsonNode doneA = first(a, "TournamentCompleted");
                assertEquals(misty.accountId, doneA.get("finalStandings").get(0).get("accountID").asText());
                assertEquals(0, doneA.get("prizes").size());
                assertEquals(900, env.coins(ash));
                JsonNode doneM = first(drain(misty), "TournamentCompleted");
                assertEquals(500, doneM.get("prizes").get(0).get("rewardAmount").asInt());
                assertEquals(1400, env.coins(misty));
                assertEquals(0, env.tournaments.live().activeCount());
                // A late engine callback for the aborted game is ignored.
                runner.finish(req.gameId(), ash.accountId, TournamentMatchRunner.Outcome.COMPLETED);
                assertEquals(1400, env.coins(misty));
            }
        }
    }

    @Test
    void offlinePlayerForfeitsTheNextRound() throws Exception {
        FakeTournamentMatchRunner runner = new FakeTournamentMatchRunner(Mode.AUTO);
        try (TournamentTestSupport env = new TournamentTestSupport(runner, false, null)) {
            env.seed("t4", definition("Cup of Four", 4, true, 100));
            String[] d = new String[1];
            List<SocialTestSupport.Client> cs = new ArrayList<>();
            try {
                for (String n : List.of("A", "B", "C", "D")) cs.add(player(env, n, d));
                for (SocialTestSupport.Client c : cs) {
                    join(env, c, "t4", env.services.players().decks(c.accountId, false).get(0).id(), 1);
                    drain(c);
                }
                // AUTO: seat 0 of each round-1 table won; round 2 is waiting on its countdown.
                LiveTournament t = env.tournaments.live().activeTournaments().get(0);
                assertEquals(1, t.currentRound());
                assertEquals(2, runner.requests.size());
                String w0 = runner.requests.get(0).seats().get(0).accountId();
                String w1 = runner.requests.get(1).seats().get(0).accountId();
                SocialTestSupport.Client gone = cs.stream().filter(c -> c.accountId.equals(w0)).findFirst().orElseThrow();
                gone.close();
                awaitOffline(env, w0);
                env.scheduler.runAll();

                // w0 was offline at the final: w1 advanced without a game and won the tournament.
                assertEquals(0, env.tournaments.live().activeCount());
                assertEquals(2, runner.requests.size()); // no game was started for the final
                SocialTestSupport.Client champ = cs.stream().filter(c -> c.accountId.equals(w1)).findFirst().orElseThrow();
                JsonNode done = first(drain(champ), "TournamentCompleted");
                assertEquals(w1, done.get("finalStandings").get(0).get("accountID").asText());
                assertEquals(w0, done.get("finalStandings").get(1).get("accountID").asText());
                assertEquals(1000 - 100 + 500, env.coins(champ));
                // The offline finalist still gets the second prize (granted in the database).
                assertEquals(1000 - 100 + 100, env.services.players().wallet(w0).coins());
            } finally {
                for (SocialTestSupport.Client c : cs) c.close();
            }
        }
    }

    @Test
    void unavailableMatchEngineCancelsAndRefunds() throws Exception {
        FakeTournamentMatchRunner runner = new FakeTournamentMatchRunner(Mode.REFUSE);
        try (TournamentTestSupport env = new TournamentTestSupport(runner, false, null)) {
            env.seed("t-two", definition("Duel", 2, true, 100));
            String[] d1 = new String[1], d2 = new String[1];
            try (SocialTestSupport.Client ash = player(env, "Ash", d1); SocialTestSupport.Client misty = player(env, "Misty", d2)) {
                join(env, ash, "t-two", d1[0], 1);
                drain(ash);
                join(env, misty, "t-two", d2[0], 1);
                for (SocialTestSupport.Client c : List.of(ash, misty)) {
                    List<JsonNode> msgs = drain(c);
                    JsonNode cancelled = first(msgs, "TournamentCancelled");
                    assertEquals("t-two", cancelled.get("tournamentID").asText());
                    assertTrue(names(msgs).contains("CurrentWallet"));
                    assertEquals(1000, env.coins(c));
                    assertTrue(all(msgs, "TournamentCompleted").isEmpty());
                }
                assertEquals(0, env.tournaments.live().activeCount());
                // Both can queue again.
                join(env, ash, "t-two", d1[0], 2);
                assertEquals("TournamentQueueJoined", ash.next().get("name").asText());
            }
        }
        // The shipped default runner reports "cannot start" in the same way.
        try (TournamentTestSupport env = new TournamentTestSupport(TournamentMatchRunner.UNAVAILABLE, false, null)) {
            env.seed("t-two", definition("Duel", 2, true, 100));
            String[] d1 = new String[1], d2 = new String[1];
            try (SocialTestSupport.Client ash = player(env, "Ash", d1); SocialTestSupport.Client misty = player(env, "Misty", d2)) {
                join(env, ash, "t-two", d1[0], 1);
                join(env, misty, "t-two", d2[0], 1);
                assertFalse(all(drain(misty), "TournamentCancelled").isEmpty());
                assertEquals(1000, env.coins(misty));
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static com.fasterxml.jackson.databind.node.ObjectNode definitionWithFormat(String name, String format) {
        com.fasterxml.jackson.databind.node.ObjectNode d = definition(name, 4, true, 0);
        d.put("format", format);
        return d;
    }

    private static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static void drainUntil(SocialTestSupport.Client c, String name) throws Exception {
        until(c, name);
    }
}
