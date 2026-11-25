package com.ptcgo.revival.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.TournamentRepository;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.packet.ClientContext;
import com.ptcgo.revival.packet.WalletSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Legacy Events-scene live tournaments: the Events channel subscribers, the per-tournament waiting queues and the
 * running brackets. Port of {@code LiveTournamentManager} in tournaments/live.py.
 *
 * <p>Python ran everything on one asyncio loop; here every state-changing entry point synchronises on this object (the
 * lock is re-entrant, so a match runner may report a result synchronously from inside {@code start}). Sends to clients
 * are queued writes and never block on another connection while the lock is held.
 */
public final class LiveTournamentManager {
    private static final Logger LOG = LoggerFactory.getLogger(LiveTournamentManager.class);

    /** Delays that Python hard-codes; configurable so tests do not wait. */
    public record Timings(long[] nudgeDelaysMs, int minRoundDelaySeconds) {
        public static final Timings DEFAULT = new Timings(new long[] {1500, 10_000, 30_000}, 3);
    }

    private final ClientRegistry registry;
    private final TournamentRepository repo;
    private final TournamentManager tournaments;
    private final TournamentMatchRunner runner;
    private final Scheduler scheduler;
    private final Timings timings;
    private final Random random;

    private final Set<ClientContext> subscribers = new LinkedHashSet<>();
    private final Map<String, List<LiveTournament.Participant>> queues = new HashMap<>(); // lower-case tournament id
    private final Map<String, LiveTournament> active = new LinkedHashMap<>();             // activeTournamentID

    public LiveTournamentManager(ClientRegistry registry, TournamentRepository repo, TournamentManager tournaments,
                                 TournamentMatchRunner runner, Scheduler scheduler, Timings timings, Random random) {
        this.registry = registry;
        this.repo = repo;
        this.tournaments = tournaments;
        this.runner = runner;
        this.scheduler = scheduler;
        this.timings = timings;
        this.random = random;
    }

    // ---- collaborators for LiveTournament --------------------------------------------------------

    TournamentRepository repo() { return repo; }
    TournamentMatchRunner runner() { return runner; }
    Timings timings() { return timings; }
    Random random() { return random; }

    void schedule(long delayMs, Runnable task) {
        scheduler.schedule(delayMs, () -> {
            synchronized (this) {
                task.run();
            }
        });
    }

    /** The participant's live connection, or null while they are offline (reconnects are picked up automatically). */
    ClientContext resolveClient(LiveTournament.Participant p) {
        return registry.find(p.accountId).orElse(null);
    }

    void sendTo(ClientContext client, String name, Map<String, ?> payload) {
        if (client == null) return;
        try {
            client.send(name, payload, 0);
        } catch (RuntimeException e) {
            LOG.error("[LiveTournament] send {} failed", name, e);
        }
    }

    void pushWallet(ClientContext client) {
        if (client == null) return;
        try {
            WalletSync.pushWallet(client);
        } catch (Exception e) {
            LOG.error("[LiveTournament] wallet push failed", e);
        }
    }

    void finishTournament(LiveTournament t) {
        active.remove(t.activeId());
    }

    // ---- lookups ---------------------------------------------------------------------------------

    /** The waiting players (live list; callers outside this package get a copy through {@link #usernamesInQueue}). */
    private List<LiveTournament.Participant> queueFor(String tournamentId) {
        return queues.computeIfAbsent(tournamentId.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
    }

    public synchronized int queueSize(String tournamentId) {
        return queueFor(tournamentId).size();
    }

    public synchronized List<String> usernamesInQueue(String tournamentId) {
        return queueFor(tournamentId).stream().map(p -> p.username).toList();
    }

    /** {tournamentID(lower-case): queue size} for every tournament the client displays (must be non-null). */
    public synchronized Map<String, Object> queueCounts() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (TournamentDef t : tournaments.visible()) {
            m.put(t.tournamentId().toLowerCase(Locale.ROOT), queueFor(t.tournamentId()).size());
        }
        return m;
    }

    public synchronized LiveTournament activeById(String activeId) {
        return active.get(activeId == null ? "" : activeId.toLowerCase(Locale.ROOT));
    }

    /** The unfinished bracket this account is still seated in. */
    public synchronized LiveTournament findActiveFor(String accountId) {
        for (LiveTournament t : active.values()) {
            LiveTournament.Participant p = t.participant(accountId);
            if (p != null && !t.completed() && !p.withdrawn()) return t;
        }
        return null;
    }

    /** The lower-case tournament id whose queue holds the account, or null. */
    public synchronized String findQueued(String accountId) {
        for (Map.Entry<String, List<LiveTournament.Participant>> e : queues.entrySet()) {
            for (LiveTournament.Participant p : e.getValue()) {
                if (p.accountId.equals(accountId)) return e.getKey();
            }
        }
        return null;
    }

    public synchronized int activeCount() {
        return active.size();
    }

    // ---- queue flow ------------------------------------------------------------------------------

    /** Adds to the queue (fee already charged) and starts the bracket when it is full. */
    public synchronized void joinQueue(ClientContext client, TournamentDef tournament, JsonNode deck) {
        String tid = tournament.tournamentId().toLowerCase(Locale.ROOT);
        List<LiveTournament.Participant> queue = queueFor(tid);
        queue.add(new LiveTournament.Participant(client.account().accountId(), client.account().displayName(), deck));
        broadcastQueueStatus(tid);
        pushQueueUsers(tid);
        if (queue.size() >= tournament.maxSize()) {
            List<LiveTournament.Participant> players = new ArrayList<>(queue.subList(0, tournament.maxSize()));
            queue.subList(0, tournament.maxSize()).clear();
            broadcastQueueStatus(tid);
            LiveTournament live = new LiveTournament(this, tournament, players);
            active.put(live.activeId(), live);
            LOG.info("[LiveTournament] Starting '{}' ({}) with {} players",
                    Json.strOr(tournament.definition(), "name", ""), live.activeId(), players.size());
            live.start();
        }
    }

    /**
     * Removes the account from the queue and refunds the entry fee. {@code notify} (may be null, e.g. on disconnect)
     * gets the wallet push. False when the account was not queued there.
     */
    public synchronized boolean leaveQueue(String accountId, String tournamentId, ClientContext notify) {
        String tid = tournamentId.toLowerCase(Locale.ROOT);
        List<LiveTournament.Participant> queue = queueFor(tid);
        LiveTournament.Participant entry = queue.stream().filter(p -> p.accountId.equals(accountId)).findFirst().orElse(null);
        if (entry == null) return false;
        queue.remove(entry);
        TournamentDef tournament = tournaments.get(tid);
        if (tournament != null) {
            try {
                repo.refundFees(entry.accountId, tournament.legacyEntryFees());
            } catch (Exception e) {
                LOG.error("[LiveTournament] fee refund failed for {}", entry.username, e);
            }
            pushWallet(notify);
        }
        broadcastQueueStatus(tid);
        pushQueueUsers(tid);
        return true;
    }

    private void broadcastQueueStatus(String tid) {
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("tournamentID", tid);
        packet.put("size", queueFor(tid).size());
        for (ClientContext c : new ArrayList<>(subscribers)) sendTo(c, "TournamentQueueStatus", packet);
        for (LiveTournament.Participant p : new ArrayList<>(queueFor(tid))) {
            ClientContext c = registry.find(p.accountId).orElse(null);
            if (c != null && !subscribers.contains(c)) sendTo(c, "TournamentQueueStatus", packet);
        }
    }

    private void pushQueueUsers(String tid) {
        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("usernames", usernamesInQueue(tid));
        for (LiveTournament.Participant p : new ArrayList<>(queueFor(tid))) {
            sendTo(registry.find(p.accountId).orElse(null), "UsersInTournamentQueue", packet);
        }
    }

    // ---- results / membership --------------------------------------------------------------------

    /** The match runner's callback: a game of this bracket ended. */
    public synchronized void onMatchResult(String activeId, TournamentMatchRunner.MatchResult result) {
        LiveTournament t = active.get(activeId);
        if (t != null) t.recordResult(result.gameId(), result.winnerAccountId(), false);
    }

    /** LeaveActiveTournament. */
    public synchronized void withdraw(String activeId, String accountId) {
        LiveTournament t = active.get(activeId == null ? "" : activeId.toLowerCase(Locale.ROOT));
        if (t != null) t.withdraw(accountId);
    }

    public synchronized void subscribe(ClientContext c) {
        subscribers.add(c);
    }

    public synchronized void unsubscribe(ClientContext c) {
        subscribers.remove(c);
    }

    public synchronized boolean isSubscribed(ClientContext c) {
        return subscribers.contains(c);
    }

    /**
     * Disconnect cleanup: leave the Events channel and any waiting queue (with fee refund). Seated players of a running
     * bracket stay seated: a reconnect is picked up by {@code resolveClient}, and a round start forfeits no-shows.
     */
    public synchronized void handleDisconnect(ClientContext c) {
        unsubscribe(c);
        if (c.account() == null) return;
        String tid = findQueued(c.account().accountId());
        if (tid != null) leaveQueue(c.account().accountId(), tid, null);
    }

    /** Unmodifiable snapshot, for tests. */
    public synchronized List<LiveTournament> activeTournaments() {
        return Collections.unmodifiableList(new ArrayList<>(active.values()));
    }
}
