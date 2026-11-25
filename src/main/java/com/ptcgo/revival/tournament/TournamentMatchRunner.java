package com.ptcgo.revival.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.packet.ClientContext;

import java.util.List;
import java.util.function.Consumer;

/**
 * The seam between the live bracket (ported) and the match engine (the {@code versus} branch). The bracket decides who
 * plays whom; the runner plays the game.
 *
 * <p>Python equivalent ({@code LiveTournament._start_or_forfeit}): build a pairing {@code queue_name =
 * "Tournament_<tournamentID>"} with {@code legacy_tournament = {active_id, tournament_id}} (the game then sends
 * {@code gameOptions["TournamentID"]}), register it in {@code pending_pairings[gameId]}, run the ready check, and when
 * the game ends call {@code on_result(game_id, winner_account_id)}.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>{@link #start} returns {@code true} once the match is launched (ready check dispatched); the callback is then
 *       invoked exactly once, from any thread, when the match has a winner (normal end, concede, timeout or forfeit
 *       are all just a winner).</li>
 *   <li>It returns {@code false} (or throws) when the match cannot be launched; the callback is then never invoked and
 *       the bracket cancels the whole tournament and refunds the entry fees ({@code TournamentCancelled}).</li>
 *   <li>The game must carry {@code request.tournamentId()} as {@code gameOptions["TournamentID"]}.</li>
 * </ul>
 */
public interface TournamentMatchRunner {
    /** One player of a match. {@code client} is the live connection at launch time. */
    record Seat(String accountId, String username, ClientContext client, JsonNode deck) {}

    /** {@code gameId} identifies the matchup; the callback result must carry it back. */
    record MatchRequest(String gameId, String queueName, String tournamentId, String activeId, List<Seat> seats) {}

    enum Outcome { COMPLETED, CONCEDED, TIMED_OUT }

    /** {@code winnerAccountId} must be one of the seats; anything else is treated as seat 0 winning (as Python). */
    record MatchResult(String gameId, String winnerAccountId, Outcome outcome) {}

    boolean start(MatchRequest request, Consumer<MatchResult> onResult);

    /**
     * The bracket decided the matchup without the game (a player left the tournament, or it was cancelled): stop the
     * running game, if any. Its result callback, if it still arrives, is ignored. Default: nothing to stop.
     */
    default void abort(String gameId) {}

    /** Default: no engine, every match fails to launch. */
    TournamentMatchRunner UNAVAILABLE = (request, onResult) -> false;
}
