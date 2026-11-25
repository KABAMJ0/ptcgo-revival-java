package com.ptcgo.revival.game.match;

import com.ptcgo.revival.game.engine.GameSession;
import com.ptcgo.revival.tournament.TournamentMatchRunner;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * {@link TournamentMatchRunner} backed by the {@link MatchManager}. A game is "live" from {@link #start} until its
 * single result is delivered or it is aborted; the set makes the callback fire at most once and never after an abort.
 */
public final class MatchManagerTournamentRunner implements TournamentMatchRunner {
    private final MatchManager manager;
    private final Set<String> live = ConcurrentHashMap.newKeySet();

    public MatchManagerTournamentRunner(MatchManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean start(MatchRequest request, Consumer<MatchResult> onResult) {
        live.add(request.gameId());
        GameSession.GameResultListener listener = (gameId, winnerId, reason) -> {
            if (!live.remove(gameId)) return; // already delivered, or aborted
            onResult.accept(new MatchResult(gameId, winnerId, outcomeOf(reason)));
        };
        boolean launched = manager.startTournamentMatch(request.gameId(), request.tournamentId(), request.seats(), listener);
        if (!launched) live.remove(request.gameId());
        return launched;
    }

    @Override
    public void abort(String gameId) {
        live.remove(gameId);
        manager.abortTournamentMatch(gameId);
    }

    /**
     * The engine reports only the end-of-game text, so the outcome is read from it: a concession ends with
     * "<name> conceded", a timeout with "ran out of time ...", anything else is a completed game.
     */
    static Outcome outcomeOf(String reason) {
        if (reason == null) return Outcome.COMPLETED;
        if (reason.endsWith(" conceded")) return Outcome.CONCEDED;
        if (reason.contains("ran out of time")) return Outcome.TIMED_OUT;
        return Outcome.COMPLETED;
    }
}
