package com.ptcgo.revival.tournament;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Deterministic stand-in for the match engine. */
public final class FakeTournamentMatchRunner implements TournamentMatchRunner {
    public enum Mode { AUTO, MANUAL, REFUSE }

    public volatile Mode mode;
    /** AUTO mode: who wins. Default: the first seat. */
    public volatile Function<MatchRequest, String> winnerPicker = r -> r.seats().get(0).accountId();
    public final List<MatchRequest> requests = new ArrayList<>();
    public final List<String> aborted = new ArrayList<>();
    private final Map<String, Consumer<MatchResult>> pending = new LinkedHashMap<>();

    public FakeTournamentMatchRunner(Mode mode) {
        this.mode = mode;
    }

    @Override
    public synchronized boolean start(MatchRequest request, Consumer<MatchResult> onResult) {
        requests.add(request);
        switch (mode) {
            case REFUSE:
                return false;
            case AUTO:
                // Reports synchronously from inside start, the harshest re-entrancy the bracket must survive.
                onResult.accept(new MatchResult(request.gameId(), winnerPicker.apply(request), Outcome.COMPLETED));
                return true;
            default:
                pending.put(request.gameId(), onResult);
                return true;
        }
    }

    @Override
    public synchronized void abort(String gameId) {
        aborted.add(gameId);
    }

    /** MANUAL mode: the game ends. */
    public void finish(String gameId, String winnerAccountId, Outcome outcome) {
        Consumer<MatchResult> cb;
        synchronized (this) {
            cb = pending.remove(gameId);
        }
        if (cb == null) throw new IllegalStateException("no pending match " + gameId);
        cb.accept(new MatchResult(gameId, winnerAccountId, outcome));
    }

    public synchronized List<MatchRequest> pendingRequests() {
        return requests.stream().filter(r -> pending.containsKey(r.gameId())).toList();
    }
}
