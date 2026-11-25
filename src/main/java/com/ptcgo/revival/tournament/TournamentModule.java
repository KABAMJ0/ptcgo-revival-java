package com.ptcgo.revival.tournament;

import com.ptcgo.revival.Services;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.TournamentRepository;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.packet.ClientContext;
import com.ptcgo.revival.packet.PacketHandler;
import com.ptcgo.revival.packet.TournamentHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles tournaments (async runs + leaderboards, live Events-scene brackets) and exposes the disconnect hook, in the
 * style of {@code SocialModule}: modules are found through the connection's client registry, so {@code Services} needs
 * no change and two servers in one JVM (tests) stay separate.
 */
public final class TournamentModule {
    private static final Logger LOG = LoggerFactory.getLogger(TournamentModule.class);
    private static final Map<ClientRegistry, TournamentModule> INSTALLED = new ConcurrentHashMap<>();

    private final TournamentRepository repo;
    private final TournamentManager tournaments;
    private final LiveTournamentManager live;
    private final TournamentHandler handler;

    private TournamentModule(Services services, TournamentMatchRunner runner, AsyncTournamentMatchmaker matchmaker,
                             TournamentDeckChecker checker, Scheduler scheduler, LiveTournamentManager.Timings timings,
                             Random random) {
        Database db = services.players().database();
        this.repo = new TournamentRepository(db);
        this.tournaments = new TournamentManager(repo);
        this.live = new LiveTournamentManager(services.registry(), repo, tournaments, runner, scheduler, timings, random);
        this.handler = new TournamentHandler(repo, tournaments, live, matchmaker, checker);
    }

    /** Production setup: real timers, Python's delays, the server's own deck validator. Needs {@code Database.migrate()}. */
    public static TournamentModule create(Services services, TournamentMatchRunner runner, AsyncTournamentMatchmaker matchmaker) {
        return create(services, runner, matchmaker, TournamentDeckChecker.of(services.deckValidator()), Scheduler.system(),
                LiveTournamentManager.Timings.DEFAULT, new Random());
    }

    /** Full control (tests: fake scheduler, short delays, seeded shuffle, custom deck checker). */
    public static TournamentModule create(Services services, TournamentMatchRunner runner, AsyncTournamentMatchmaker matchmaker,
                                          TournamentDeckChecker checker, Scheduler scheduler,
                                          LiveTournamentManager.Timings timings, Random random) {
        TournamentModule m = new TournamentModule(services, runner, matchmaker, checker, scheduler, timings, random);
        INSTALLED.put(services.registry(), m);
        return m;
    }

    /** Handler groups to add to the {@code PacketRouter}. */
    public List<PacketHandler> handlers() {
        return List.of(handler);
    }

    public TournamentRepository repository() { return repo; }
    public TournamentManager tournaments() { return tournaments; }
    public LiveTournamentManager live() { return live; }

    /** The module installed for this connection's server, or null. */
    public static TournamentModule of(ClientContext c) {
        return INSTALLED.get(c.services().registry());
    }

    /**
     * Hook: in ClientConnection.close() after the registry unregister, for authenticated connections (next to
     * {@code SocialModule.onDisconnect}). Leaves the Events channel and any waiting queue with a fee refund. Runs on a
     * virtual thread for the same reason as the social hook: close() can be entered from a failed write while holding
     * that connection's write lock, and the cleanup sends to other clients.
     */
    public static void onDisconnect(ClientContext c) {
        TournamentModule m = of(c);
        if (m == null || c.account() == null) return;
        Thread.startVirtualThread(() -> m.disconnectNow(c));
    }

    /** Synchronous variant of {@link #onDisconnect} (tests, orderly shutdown). */
    public void disconnectNow(ClientContext c) {
        try {
            live.handleDisconnect(c);
        } catch (RuntimeException e) {
            LOG.error("[Tournaments] cleanup on disconnect failed", e);
        }
    }

    /**
     * Engine hook for async-tournament games (Python {@code GameSession._record_tournament_results}). When a game that was
     * queued through {@link AsyncTournamentMatchmaker#enqueue} ends, call this once per game with each player's entry id.
     * It records the result for both runs, pushes {@code AsyncTournamentProgressUpdated} and, when a run reached its win /
     * loss / game cap, finishes it with {@code AsyncTournamentRewards}. The engine pushes the wallet afterwards (as
     * Python's end_game does).
     *
     * @param entries  accountId to entryId of every player of the game that has a run
     * @param players  the live connection of each player (a missing / offline one is just not messaged)
     * @param names    accountId to display name, written to the opponent history rows
     */
    public void recordAsyncGame(String tournamentId, Map<String, String> entries, String winnerAccountId,
                                Map<String, ClientContext> players, Map<String, String> names) {
        TournamentDef t = tournaments.get(tournamentId);
        JsonNode definition = t == null ? null : t.definition();
        JsonNode run = t == null ? null : t.runConfig();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String pid = e.getKey();
            String entryId = e.getValue();
            if (entryId == null || entryId.isEmpty()) continue;
            String opponent = entries.keySet().stream().filter(k -> !k.equals(pid)).findFirst().orElse("");
            try {
                TournamentRepository.GameRecorded rec = repo.recordGameResult(entryId, pid.equals(winnerAccountId),
                        opponent, names.getOrDefault(opponent, ""), run);
                if (rec == null) continue;
                ClientContext client = players.get(pid);
                TournamentRepository.Entry entry = rec.entry();
                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("tournamentID", entry.tournamentId());
                progress.put("entryID", entryId);
                progress.put("wins", entry.wins());
                progress.put("losses", entry.losses());
                progress.put("rank", 0);
                progress.put("tiebreakers", entry.tiebreakers());
                if (client != null) client.send("AsyncTournamentProgressUpdated", progress, 0);
                if (rec.runComplete()) {
                    TournamentRepository.Finished fin = repo.finishEntry(entryId, pid, definition, false);
                    if (fin != null && client != null) {
                        Map<String, Object> rewards = new LinkedHashMap<>();
                        rewards.put("entryID", entryId);
                        rewards.put("wins", fin.entry().wins());
                        rewards.put("losses", fin.entry().losses());
                        rewards.put("rewards", TournamentDef.clientRewards(fin.granted()));
                        client.send("AsyncTournamentRewards", rewards, 0);
                    }
                }
            } catch (Exception ex) {
                LOG.error("[Tournaments] recording async game result failed for {}", pid, ex);
            }
        }
    }

    /** Re-reads the definitions from the database (after an admin edit). */
    public void reload() {
        tournaments.reload();
    }
}
