package com.ptcgo.revival.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.packet.ClientContext;

/**
 * Seam for {@code StartAsyncTournamentGame}. Python completes the RPC with an empty reply and then calls
 * {@code GameSessionManager.add_to_queue(client, "AsyncTournament_<tournamentID>", deck, {}, 0,
 * tournament_context={tournament_id, entry_id})}; two queued players with a tournament context are paired and the game
 * records results for both runs. After each such game the engine must call
 * {@link TournamentModule#recordAsyncGame}.
 */
public interface AsyncTournamentMatchmaker {
    /**
     * Whether queueing works at all. When false the handler answers {@code StartAsyncTournamentGameError} instead of
     * acknowledging (checked before the acknowledgement so the ack always precedes {@code MatchQueueEntered}).
     */
    boolean isAvailable();

    /** Put the client in the matchmaking queue; the empty RPC acknowledgement has already been sent. */
    void enqueue(ClientContext client, String queueName, JsonNode deck, String tournamentId, String entryId);

    AsyncTournamentMatchmaker UNAVAILABLE = new AsyncTournamentMatchmaker() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void enqueue(ClientContext client, String queueName, JsonNode deck, String tournamentId, String entryId) {
            throw new IllegalStateException("matchmaking unavailable");
        }
    };
}
