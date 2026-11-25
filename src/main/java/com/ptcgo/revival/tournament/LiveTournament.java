package com.ptcgo.revival.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.ptcgo.revival.db.TournamentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One running single-elimination bracket (Python {@code LiveTournament}). All methods run under the owning
 * {@link LiveTournamentManager}'s lock.
 */
public final class LiveTournament {
    private static final Logger LOG = LoggerFactory.getLogger(LiveTournament.class);
    private static final String ZERO_GUID = "00000000-0000-0000-0000-000000000000";

    /** A seated player. */
    public static final class Participant {
        public final String accountId;
        public final String username;
        public final JsonNode deck;
        boolean withdrawn;
        int wins;
        int eliminatedRound; // 0 = still alive

        public Participant(String accountId, String username, JsonNode deck) {
            this.accountId = accountId;
            this.username = username;
            this.deck = deck;
        }

        public boolean withdrawn() { return withdrawn; }
        public int wins() { return wins; }
        public int eliminatedRound() { return eliminatedRound; }

        Map<String, Object> identity() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountID", accountId);
            m.put("username", username);
            return m;
        }
    }

    /** One table of one round. */
    public static final class Matchup {
        final String gameId = UUID.randomUUID().toString();
        final int round; // 1-based
        final int table; // 0-based
        final List<Participant> players;
        Participant winner;

        Matchup(int round, int table, List<Participant> players) {
            this.round = round;
            this.table = table;
            this.players = players;
        }

        public String gameId() { return gameId; }
        public int round() { return round; }
        public int table() { return table; }
        public List<Participant> players() { return players; }
        public Participant winner() { return winner; }

        /** players must be EXACTLY 2 entries: the client's G.j.get_Player1 NREs otherwise. */
        Map<String, Object> toWire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gameID", gameId);
            m.put("winner", winner == null ? null : winner.identity());
            m.put("round", round);
            m.put("table", table);
            m.put("players", players.stream().map(Participant::identity).toList());
            return m;
        }
    }

    private final LiveTournamentManager manager;
    private final TournamentDef definition;
    private final String activeId = UUID.randomUUID().toString();
    private final List<Participant> participants;
    private final List<Matchup> matchups = new ArrayList<>();
    private int currentRound;
    private boolean completed;

    LiveTournament(LiveTournamentManager manager, TournamentDef definition, List<Participant> participants) {
        this.manager = manager;
        this.definition = definition;
        this.participants = new ArrayList<>(participants);
    }

    public String activeId() { return activeId; }
    public TournamentDef definition() { return definition; }
    public boolean completed() { return completed; }
    public int currentRound() { return currentRound; }
    public List<Participant> participants() { return Collections.unmodifiableList(participants); }
    public List<Matchup> matchups() { return Collections.unmodifiableList(matchups); }

    // ---- wire shapes -----------------------------------------------------------------------------

    public Map<String, Object> progress() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeTournamentID", activeId);
        m.put("tournamentData", definition.toLegacyDict());
        m.put("currentRound", currentRound);
        m.put("matchups", matchups.stream().map(Matchup::toWire).toList());
        m.put("roundLength", definition.roundLength());
        return m;
    }

    /** Versus Reward.to_dict: rewardProductID is null when there is no product (the client derefs it unguarded). */
    static Map<String, Object> prizeWire(JsonNode r, int index) {
        String product = Json.strOr(r, "rewardProductID", "");
        JsonNode desc = r.get("rewardDescription");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.path("name").asText(""));
        m.put("rewardType", r.path("rewardType").asText("Tokens"));
        m.put("rewardAmount", r.has("rewardAmount") ? r.get("rewardAmount") : JsonNodeFactory.instance.numberNode(0));
        m.put("rewardProductID", product.isEmpty() || product.equals(ZERO_GUID) ? null : product);
        m.put("rewardCurrency", Json.strOr(r, "rewardCurrency", ""));
        m.put("rewardDescription", Json.truthy(desc) ? desc : Map.of("id", ""));
        m.put("rewardReason", Json.strOr(r, "rewardReason", ""));
        m.put("index", index);
        return m;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    void start() {
        Collections.shuffle(participants, manager.random());
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("activeTournamentID", activeId);
        started.put("size", participants.size());
        broadcast("TournamentStarted", started);
        beginRound(1, new ArrayList<>(participants));
    }

    private void beginRound(int roundNo, List<Participant> players) {
        currentRound = roundNo;
        List<Matchup> fresh = new ArrayList<>();
        for (int table = 0; table < players.size() / 2; table++) {
            fresh.add(new Matchup(roundNo, table, new ArrayList<>(players.subList(table * 2, table * 2 + 2))));
        }
        matchups.addAll(fresh);
        pushRoundUpdate();
        for (Matchup m : fresh) {
            if (completed) return; // a failed launch cancelled the tournament
            startOrForfeit(m);
        }
    }

    /** Launches the game, or auto-advances when a seat is withdrawn or offline. */
    private void startOrForfeit(Matchup matchup) {
        List<Participant> live = matchup.players.stream()
                .filter(p -> !p.withdrawn && manager.resolveClient(p) != null).toList();
        if (live.size() < 2) {
            Participant winner = live.isEmpty() ? matchup.players.get(0) : live.get(0);
            recordResult(matchup.gameId, winner.accountId, true);
            return;
        }
        List<TournamentMatchRunner.Seat> seats = matchup.players.stream()
                .map(p -> new TournamentMatchRunner.Seat(p.accountId, p.username, manager.resolveClient(p), p.deck)).toList();
        TournamentMatchRunner.MatchRequest request = new TournamentMatchRunner.MatchRequest(matchup.gameId,
                "Tournament_" + definition.tournamentId(), definition.tournamentId(), activeId, seats);
        boolean launched;
        try {
            launched = manager.runner().start(request, result -> manager.onMatchResult(activeId, result));
        } catch (RuntimeException e) {
            LOG.error("[LiveTournament {}] match launch failed", shortId(), e);
            launched = false;
        }
        if (!launched) cancel("match could not be started");
    }

    void recordResult(String gameId, String winnerAccountId, boolean forfeit) {
        Matchup matchup = matchups.stream().filter(m -> m.gameId.equals(gameId)).findFirst().orElse(null);
        if (matchup == null || matchup.winner != null || completed) return;
        Participant winner = matchup.players.stream().filter(p -> p.accountId.equals(winnerAccountId))
                .findFirst().orElse(matchup.players.get(0));
        matchup.winner = winner;
        winner.wins++;
        for (Participant p : matchup.players) {
            if (p != winner) p.eliminatedRound = matchup.round;
        }
        LOG.info("[LiveTournament {}] R{} T{} winner: {}{}", shortId(), matchup.round, matchup.table, winner.username,
                forfeit ? " (forfeit)" : "");
        pushRoundUpdate();
        checkRoundComplete(matchup.round);
        if (!forfeit && !completed) {
            // After EOG the client needs a nudge back to the Tournament scene; while still on Playmat the push is ignored,
            // so repeat it briefly (1.5 s, then +10 s, then +30 s).
            nudge(matchup.players, 0);
        }
    }

    private void nudge(List<Participant> players, int step) {
        long[] delays = manager.timings().nudgeDelaysMs();
        if (step >= delays.length) return;
        manager.schedule(delays[step], () -> {
            if (completed) return;
            for (Participant p : players) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("activeTournamentID", activeId);
                sendTo(p, "UserInActiveTournament", m);
            }
            nudge(players, step + 1);
        });
    }

    private void checkRoundComplete(int roundNo) {
        List<Matchup> round = matchups.stream().filter(m -> m.round == roundNo).toList();
        if (round.stream().anyMatch(m -> m.winner == null)) return;
        List<Participant> winners = round.stream().sorted(Comparator.comparingInt(m -> m.table))
                .map(m -> m.winner).toList();
        if (winners.size() == 1) {
            complete(winners.get(0));
            return;
        }
        int delay = Math.max(manager.timings().minRoundDelaySeconds(), definition.delayBetweenRounds());
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("activeTournamentID", activeId);
        next.put("countdown", delay);
        next.put("suppressPopup", false);
        broadcast("TournamentNextRoundStarting", next);
        manager.schedule(delay * 1000L, () -> {
            if (!completed) beginRound(roundNo + 1, new ArrayList<>(winners));
        });
    }

    /** Champion first, then by elimination round (later is better), then wins; ties keep seat order. */
    List<Participant> finalStandings() {
        List<Participant> s = new ArrayList<>(participants);
        s.sort(Comparator.<Participant, Boolean>comparing(p -> p.eliminatedRound == 0)
                .thenComparingInt(p -> p.eliminatedRound).thenComparingInt(p -> p.wins).reversed());
        return s;
    }

    private void complete(Participant champion) {
        completed = true;
        List<Participant> standings = finalStandings();
        JsonNode prizeTable = definition.runConfig().path("prizeTable");
        Map<String, Object> base = progress();
        int place = 0;
        for (Participant participant : standings) {
            place++;
            List<JsonNode> granted = TournamentRepository.prizeRewardsFor(prizeTable, place);
            if (!granted.isEmpty() && !participant.withdrawn) {
                try {
                    manager.repo().grantPrizeRewards(participant.accountId, granted);
                } catch (Exception e) {
                    LOG.error("[LiveTournament {}] prize grant failed for {}", shortId(), participant.username, e);
                    granted = List.of();
                }
            } else {
                granted = List.of();
            }
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("tournamentData", base);
            done.put("finalStandings", standings.stream().map(Participant::identity).toList());
            List<Map<String, Object>> prizes = new ArrayList<>();
            for (int i = 0; i < granted.size(); i++) prizes.add(prizeWire(granted.get(i), i)); // never null: the view foreachs it
            done.put("prizes", prizes);
            sendTo(participant, "TournamentCompleted", done);
            if (!granted.isEmpty()) manager.pushWallet(manager.resolveClient(participant));
        }
        LOG.info("[LiveTournament {}] Completed, champion {}", shortId(), champion.username);
        manager.finishTournament(this);
    }

    /** The match engine could not run a game: cancel, refund everyone still in, free the seats. */
    void cancel(String reason) {
        if (completed) return;
        completed = true;
        LOG.warn("[LiveTournament {}] cancelled: {}", shortId(), reason);
        for (Matchup m : matchups) {
            if (m.winner == null) manager.runner().abort(m.gameId);
        }
        Map<String, Object> cancelled = new LinkedHashMap<>();
        cancelled.put("tournamentID", definition.tournamentId());
        cancelled.put("activeTournamentID", activeId);
        broadcast("TournamentCancelled", cancelled);
        for (Participant p : participants) {
            if (p.withdrawn || p.eliminatedRound != 0) continue;
            try {
                manager.repo().refundFees(p.accountId, definition.legacyEntryFees());
            } catch (Exception e) {
                LOG.error("[LiveTournament {}] refund failed for {}", shortId(), p.username, e);
                continue;
            }
            manager.pushWallet(manager.resolveClient(p));
        }
        manager.finishTournament(this);
    }

    // ---- membership ------------------------------------------------------------------------------

    public Participant participant(String accountId) {
        return participants.stream().filter(p -> p.accountId.equals(accountId)).findFirst().orElse(null);
    }

    /** LeaveActiveTournament: forfeit the player's unresolved matchup. */
    void withdraw(String accountId) {
        Participant participant = participant(accountId);
        if (participant == null || participant.withdrawn) return;
        participant.withdrawn = true;
        Matchup pending = matchups.stream()
                .filter(m -> m.winner == null && m.players.contains(participant)).findFirst().orElse(null);
        if (pending != null) {
            Participant opponent = pending.players.stream().filter(p -> p != participant).findFirst().orElseThrow();
            manager.runner().abort(pending.gameId);
            recordResult(pending.gameId, opponent.accountId, true);
        }
    }

    // ---- sends -----------------------------------------------------------------------------------

    private void sendTo(Participant p, String name, Map<String, Object> payload) {
        manager.sendTo(manager.resolveClient(p), name, payload);
    }

    private void broadcast(String name, Map<String, Object> payload) {
        for (Participant p : participants) sendTo(p, name, payload);
    }

    private void pushRoundUpdate() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeTournamentID", activeId);
        m.put("tournamentData", progress());
        broadcast("TournamentRoundUpdated", m);
    }

    private String shortId() {
        return activeId.substring(0, 8);
    }
}
