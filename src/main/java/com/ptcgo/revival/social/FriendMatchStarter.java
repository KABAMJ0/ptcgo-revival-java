package com.ptcgo.revival.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.packet.ClientContext;

import java.util.Map;

/**
 * The seam between friend challenges (ported: request / reject / cancel bookkeeping) and the match engine
 * (not ported). When a challenged player accepts, {@link ChallengeHandler} validates the pending challenge and then
 * calls {@link #start}. The Python equivalent builds a pairing {@code queue_name="Friend"} and runs the ready check
 * ({@code GameSessionManager.accept_challenge} -> {@code _dispatch_ready_check}).
 */
public interface FriendMatchStarter {
    /** Whether the account is currently in a match ({@code create_challenge} refuses such opponents). */
    boolean isInMatch(String accountId);

    /**
     * Start the friend match. Both clients are online and have each other's pending challenge removed. The accepter has
     * already been sent {@code MatchRequestSent}. Decks are the player's saved deck JSON when the request named a saved
     * deck, otherwise what the client sent.
     */
    void start(ClientContext challenger, JsonNode challengerDeck, ClientContext accepter, JsonNode accepterDeck,
               JsonNode matchOptions);

    /** Default: friend matches are unavailable; both parties get {@code MatchRequestWithSpecificClientFailed}. */
    FriendMatchStarter UNAVAILABLE = new FriendMatchStarter() {
        @Override
        public boolean isInMatch(String accountId) {
            return false;
        }

        @Override
        public void start(ClientContext challenger, JsonNode challengerDeck, ClientContext accepter,
                          JsonNode accepterDeck, JsonNode matchOptions) {
            Map<String, Object> failed = Map.of("failureType", Map.of("id", "Friend matches are not available on this server yet."));
            challenger.send("MatchRequestWithSpecificClientFailed", failed, 0);
            accepter.send("MatchRequestWithSpecificClientFailed", failed, 0);
        }
    };
}
