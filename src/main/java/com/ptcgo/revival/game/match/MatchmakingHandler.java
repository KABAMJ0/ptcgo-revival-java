package com.ptcgo.revival.game.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.packet.ClientContext;
import com.ptcgo.revival.packet.PacketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ptcgo.revival.game.wire.Msgs.obj;

/**
 * RequestQueueMatch, RequestSinglePlayerMatch, CancelMatchRequest and ReadyForMatchConfirmation
 * (ptcgo/packets/handlers/matchmaking.py). The friend-challenge messages live in ChallengeHandler.
 */
public final class MatchmakingHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(MatchmakingHandler.class);
    private static final byte[] EMPTY_BODY = {'{', '}'};
    private static final List<String> CHALLENGE_QUEUE_MARKERS = List.of("trainerchallenge", "trainer challenge");
    private static final List<String> CHALLENGE_OPTION_KEYS = List.of("scenarios", "scenario", "scenarioID", "scenarioId");
    static final String UNAVAILABLE_TEXT = "That opponent is not available yet.";

    private final MatchManager manager;

    public MatchmakingHandler(MatchManager manager) {
        this.manager = manager;
    }

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("RequestQueueMatch", this::requestQueueMatch);
        m.put("RequestSinglePlayerMatch", this::requestSinglePlayerMatch);
        m.put("CancelMatchRequest", this::cancelMatchRequest);
        m.put("ReadyForMatchConfirmation", this::readyForMatchConfirmation);
        return m;
    }

    /** A deck named by id is replaced with the saved deck data (normal decks, then avatar decks). */
    static JsonNode resolveFullDeck(ClientContext c, JsonNode clientDeck) {
        if (clientDeck == null || clientDeck.isMissingNode() || clientDeck.isNull() || clientDeck.isEmpty()) return null;
        String id = clientDeck.hasNonNull("deckID") ? clientDeck.get("deckID").asText() : clientDeck.path("id").asText("");
        if (c.account() != null && !id.isEmpty()) {
            PlayerRepository players = c.services().players();
            try {
                for (boolean avatar : new boolean[] {false, true}) {
                    for (PlayerRepository.DeckRow d : players.decks(c.account().accountId(), avatar)) {
                        if (d.id().equals(id) && d.deckData() != null) {
                            LOG.info("[Matchmaking] Resolved full deck data '{}' ({}) from player profile.", d.name(), id);
                            return d.deckData();
                        }
                    }
                }
            } catch (SQLException e) {
                LOG.error("[Matchmaking] deck lookup failed", e);
            }
        }
        return clientDeck;
    }

    private static void ack(ClientContext c, int rid) {
        c.sendRaw(EMPTY_BODY, rid, 0);
    }

    private static boolean isChallengeRequest(String queueName, JsonNode options) {
        String name = queueName == null ? "" : queueName.strip().toLowerCase();
        if (CHALLENGE_QUEUE_MARKERS.stream().anyMatch(name::contains)) return true;
        return options != null && CHALLENGE_OPTION_KEYS.stream().anyMatch(options::has);
    }

    private void requestQueueMatch(ClientContext c, JsonNode v, int rid, int flags) {
        if (c.account() == null) return;
        JsonNode deck = resolveFullDeck(c, v == null ? null : v.get("deck"));
        String queueName = v != null && v.hasNonNull("queueName") ? v.get("queueName").asText() : "Standard";
        JsonNode options = v != null && v.has("clientOptions") ? v.get("clientOptions") : null;
        // The Trainer Challenge's Play button is a queue request naming the Trainer in clientOptions. The league
        // opponents are not ported, so it gets the Python "no opponent available" answer.
        if (isChallengeRequest(queueName, options)) {
            LOG.info("[TCP] [{}] Trainer Challenge play on queue '{}'", c.remoteAddress(), queueName);
            ack(c, rid);
            c.send("MatchQueueEntered", obj("estimatedWaitTime", 0), 0);
            c.send("LeagueAndGymModeError", obj("msg", obj("id", UNAVAILABLE_TEXT)), 0);
            return;
        }
        LOG.info("[TCP] [{}] Player {} requesting queue entry for '{}'", c.remoteAddress(), c.account().username(), queueName);
        // Complete the RequestQueueMatch RPC first with an empty reply; MatchQueueEntered is then an independent event.
        ack(c, rid);
        manager.addToQueue(c, queueName, deck, options);
    }

    private void requestSinglePlayerMatch(ClientContext c, JsonNode v, int rid, int flags) {
        if (c.account() == null) return;
        JsonNode deck = resolveFullDeck(c, v == null ? null : v.get("deck"));
        String solitaireId = v != null && v.hasNonNull("solitaireID") ? v.get("solitaireID").asText() : "basic_bot_id";
        JsonNode options = v != null && v.has("matchOptions") ? v.get("matchOptions") : null;
        LOG.info("[TCP] [{}] Player {} requesting solo match against AI {}", c.remoteAddress(), c.account().username(), solitaireId);
        manager.startSoloMatch(c, deck, solitaireId, options, rid);
    }

    private void cancelMatchRequest(ClientContext c, JsonNode v, int rid, int flags) {
        if (c.account() == null) return;
        LOG.info("[TCP] [{}] Player {} requesting matchmaking cancellation", c.remoteAddress(), c.account().username());
        manager.removeFromQueue(c, true);
    }

    private void readyForMatchConfirmation(ClientContext c, JsonNode v, int rid, int flags) {
        if (c.account() == null || v == null) return;
        manager.confirmReady(c, v.path("gameID").asText(""));
    }
}
