package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.DailyLoginRepository;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.db.QuestRepository;
import com.ptcgo.revival.db.VersusRepository;
import com.ptcgo.revival.deck.DeckValidator;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.progression.DailyRewards;
import com.ptcgo.revival.progression.Progression;
import com.ptcgo.revival.progression.Seasons;

import com.ptcgo.revival.tournament.TournamentDeckChecker;
import com.ptcgo.revival.game.TestCatalog;
import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.engine.MatchSettings;
import com.ptcgo.revival.game.match.BanPolicy;
import com.ptcgo.revival.game.match.VersusModule;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.tournament.AsyncTournamentMatchmaker;
import com.ptcgo.revival.tournament.LiveTournamentManager;
import com.ptcgo.revival.tournament.ManualScheduler;
import com.ptcgo.revival.tournament.TournamentMatchRunner;
import com.ptcgo.revival.tournament.TournamentModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The real live-bracket path: TournamentModule drives the MatchManager-backed runner, the match is played to its end
 * by scripted clients, and the bracket announces the champion.
 */
class TournamentMatchEndToEndTest {
    private static final String TOURNAMENT = "t-live-e2e";

    /** Server with the bracket, the versus engine (its runner is the bracket's engine) and the gameplay handlers. */
    static final class Live implements AutoCloseable {
        final Services services;
        final VersusModule versus;
        final TournamentModule tournaments;
        final ManualScheduler scheduler = new ManualScheduler();
        final GameServer server;
        final int port;

        Live() throws Exception {
            // TestServices uses an empty catalog, so decks would not be playable: use the engine's test catalog.
            Path tmp = Files.createTempFile("ptcgo-tourney-e2e", ".db");
            tmp.toFile().deleteOnExit();
            Database db = new Database("jdbc:sqlite:" + tmp);
            db.migrate();
            services = new Services(new Config(), new AccountRepository(db), new PlayerRepository(db), new CasTickets(),
                    new ClientRegistry(), TestCatalog.catalog(), DeckValidator.PERMISSIVE,
                    new Progression(new QuestRepository(db), new VersusRepository(db), Seasons.loadBundled(),
                            new DailyLoginRepository(db, DailyRewards.loadBundled(), TestCatalog.catalog(),
                                    java.time.Instant::now, new Random(1))),
                    null);
            versus = VersusModule.create(services, MatchSettings.fast(), EffectRegistry.empty(), new VersusHooks(services),
                    BanPolicy.NONE, () -> TestCatalog.lightningDeck(), new Random(42));
            tournaments = TournamentModule.create(services, versus.tournamentRunner(), AsyncTournamentMatchmaker.UNAVAILABLE,
                    TournamentDeckChecker.of(services.deckValidator()), scheduler,
                    new LiveTournamentManager.Timings(new long[] {1, 2, 3}, 3), new Random(7));
            List<PacketHandler> groups = new ArrayList<>();
            groups.add(new AuthHandler());
            groups.addAll(tournaments.handlers());
            groups.addAll(versus.handlers());
            server = new GameServer(services, new PacketRouter(groups), null);
            port = server.start(0);
        }

        /** A seeded, open, free-entry two-seat bracket. */
        void seedDuel() throws Exception {
            tournaments.repository().upsertTournament(TournamentTestSupport.definition("Duel", 2, true, 0), TOURNAMENT, true);
            tournaments.reload();
        }

        /** Saves a playable deck (the same list the bots queue with) and returns its id. */
        String deckFor(VersusEndToEndTest.Bot bot) throws Exception {
            String id = UUID.randomUUID().toString();
            services.players().saveDeck(bot.accountId, id, "Lightning", TestCatalog.lightningDeck(), false);
            return id;
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }

    private static void awaitGameOver(VersusEndToEndTest.Bot b) throws Exception {
        long end = System.currentTimeMillis() + 90_000;
        while (b.completed == null && b.error == null && System.currentTimeMillis() < end) Thread.sleep(20);
        if (b.error != null) throw new AssertionError("bot " + b.name + " failed", b.error);
        assertNotNull(b.completed, "bot " + b.name + " never got GameCompleted");
    }

    /** Reads the bot's socket until its TournamentCompleted arrives (the bot thread must have finished first). */
    private static JsonNode tournamentCompleted(VersusEndToEndTest.Bot b) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode m = b.next();
            if ("TournamentCompleted".equals(m.path("name").asText())) return m.get("value");
        }
        throw new AssertionError("bot " + b.name + " never got TournamentCompleted");
    }

    @Test
    void twoPlayerLiveTournamentPlaysToTheChampion() throws Exception {
        try (Live env = new Live()) {
            env.seedDuel();
            VersusEndToEndTest.Bot ash = new VersusEndToEndTest.Bot(env.port, "Ash");
            VersusEndToEndTest.Bot misty = new VersusEndToEndTest.Bot(env.port, "Misty");
            try {
                ash.login();
                misty.login();
                ash.send("JoinTournament", Map.of("tournamentID", TOURNAMENT, "deck", env.deckFor(ash)), 1);
                misty.send("JoinTournament", Map.of("tournamentID", TOURNAMENT, "deck", env.deckFor(misty)), 1);
                // The second join fills the bracket and launches the game through the runner.
                Thread ta = Thread.ofVirtual().start(ash);
                Thread tm = Thread.ofVirtual().start(misty);

                awaitGameOver(ash);
                awaitGameOver(misty);
                ta.join(60_000);
                tm.join(60_000);
                assertEquals(ash.completed.get("gameID").asText(), misty.completed.get("gameID").asText());
                String winner = ash.completed.get("winner").asText();
                assertTrue(winner.equals(ash.accountId) || winner.equals(misty.accountId), "winner must be a player");

                // The game result reaches the bracket, which schedules the round end on the manual scheduler.
                while (env.scheduler.size() > 0) env.scheduler.runAll();

                for (VersusEndToEndTest.Bot b : List.of(ash, misty)) {
                    JsonNode done = tournamentCompleted(b);
                    assertEquals(winner, done.get("finalStandings").get(0).get("accountID").asText());
                    assertEquals(2, done.get("finalStandings").size());
                }
                assertEquals(0, env.tournaments.live().activeCount());
            } finally {
                ash.close();
                misty.close();
            }
        }
    }

    @Test
    void abortedMatchNeverReportsAResult() throws Exception {
        try (Live env = new Live()) {
            VersusEndToEndTest.Bot ash = new VersusEndToEndTest.Bot(env.port, "Ash");
            VersusEndToEndTest.Bot misty = new VersusEndToEndTest.Bot(env.port, "Misty");
            try {
                ash.login();
                misty.login();
                // Bots never answer the ready check here, so nothing can end the game on its own.
                List<TournamentMatchRunner.Seat> seats = List.of(
                        new TournamentMatchRunner.Seat(ash.accountId, "Ash",
                                env.services.registry().find(ash.accountId).orElseThrow(), TestCatalog.lightningDeck()),
                        new TournamentMatchRunner.Seat(misty.accountId, "Misty",
                                env.services.registry().find(misty.accountId).orElseThrow(), TestCatalog.lightningDeck()));
                String gameId = UUID.randomUUID().toString();
                List<TournamentMatchRunner.MatchResult> results = java.util.Collections.synchronizedList(new ArrayList<>());
                TournamentMatchRunner runner = env.versus.tournamentRunner();

                assertTrue(runner.start(new TournamentMatchRunner.MatchRequest(gameId, "Tournament_" + TOURNAMENT,
                        TOURNAMENT, "active-abort", seats), results::add));
                assertEquals(1, env.versus.manager().pendingPairingCount());

                runner.abort(gameId);
                assertEquals(0, env.versus.manager().pendingPairingCount());
                assertNull(env.versus.manager().sessionByPlayer(ash.accountId));
                assertNull(env.versus.manager().sessionByPlayer(misty.accountId));

                // Give the ready check and any game thread time to run; the callback must stay silent.
                Thread.sleep(500);
                assertTrue(results.isEmpty(), "aborted game reported a result: " + results);
                runner.abort(gameId); // a second abort is a no-op
                assertTrue(results.isEmpty());
            } finally {
                ash.close();
                misty.close();
            }
        }
    }
}
