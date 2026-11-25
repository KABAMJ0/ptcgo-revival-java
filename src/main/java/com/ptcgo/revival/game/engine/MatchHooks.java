package com.ptcgo.revival.game.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** The persistence side of a match (ladder points, coins, quests, account refresh), kept out of the engine. */
public interface MatchHooks {
    /** Ladder rating used for the client's upset-NUX comparison: 1000 + all-time versus points. */
    int rating(String accountId);

    /** Avatar item guids of the account's active avatar deck, or null for the default avatar. */
    List<String> avatarItems(String accountId);

    /** Grants match coins and awards ladder points (winner/loser) for one human. */
    void persistResult(String accountId, int coins, boolean won);

    /**
     * Credits multiplayer quest progress; returns {completedQuestsAndXPTotal, progressedQuests, rewards} or null.
     */
    QuestCredit creditQuests(String accountId, String gameId, Map<String, Integer> stats, boolean won, double startedAt);

    /** Unsolicited CurrentWallet payload for the HUD, or null. */
    Map<String, Object> walletPayload(String accountId);

    /** Full AccountUpdated payload (the client replaces ALL account attributes), or null. */
    Map<String, Object> accountPayload(String accountId);

    record QuestCredit(JsonNode completedQuestsAndXpTotal, JsonNode progressedQuests, List<JsonNode> rewards) {}

    MatchHooks NONE = new MatchHooks() {
        public int rating(String a) {
            return 1000;
        }

        public List<String> avatarItems(String a) {
            return null;
        }

        public void persistResult(String a, int c, boolean w) {}

        public QuestCredit creditQuests(String a, String g, Map<String, Integer> s, boolean w, double t) {
            return null;
        }

        public Map<String, Object> walletPayload(String a) {
            return null;
        }

        public Map<String, Object> accountPayload(String a) {
            return null;
        }
    };
}
