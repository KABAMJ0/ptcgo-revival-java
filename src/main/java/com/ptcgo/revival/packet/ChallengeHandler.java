package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.social.FriendMatchStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Friend challenges: RequestMatchWithSpecificClient / Accept / Reject / Cancel. Port of the challenge bookkeeping in
 * ptcgo/game/session/manager.py and the four handlers in matchmaking.py. Starting the match itself is delegated to
 * {@link FriendMatchStarter} (the match engine is not ported).
 *
 * <p>Skipped: the banned-deck refusal ({@code _refuse_banned_deck}); server-wide card bans are not ported at all.
 * Like Python, challenging does not require friendship. Unlike Python, challenging yourself is refused.
 */
public final class ChallengeHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ChallengeHandler.class);
    private static final byte[] EMPTY_BODY = {'{', '}'};

    private record Pending(ClientContext challenger, ClientContext opponent, JsonNode deck, JsonNode options) {}

    private final FriendMatchStarter starter;
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public ChallengeHandler(FriendMatchStarter starter) {
        this.starter = starter;
    }

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("RequestMatchWithSpecificClient", this::request);
        m.put("AcceptMatchWithSpecificClient", this::accept);
        m.put("RejectMatchWithSpecificClient", this::reject);
        m.put("CancelMatchRequestWithSpecificClient", this::cancel);
        return m;
    }

    /** The RPC reply Python sends for these requests: a bare {@code {}} body carrying the request id. */
    private static void ack(ClientContext c, int rid) {
        c.sendRaw(EMPTY_BODY, rid, 0);
    }

    private static String text(JsonNode v, String f) {
        return v != null && v.hasNonNull(f) ? v.get(f).asText() : "";
    }

    /** Python {@code _resolve_full_deck}: a deck named by id is replaced with the saved deck data. */
    private JsonNode resolveDeck(ClientContext c, JsonNode deck) throws SQLException {
        if (deck == null || deck.isMissingNode() || deck.isNull()) return null;
        String id = deck.hasNonNull("deckID") ? deck.get("deckID").asText() : deck.path("id").asText("");
        if (c.account() != null && !id.isEmpty()) {
            PlayerRepository players = c.services().players();
            for (boolean avatar : new boolean[] {false, true}) {
                for (PlayerRepository.DeckRow d : players.decks(c.account().accountId(), avatar)) {
                    if (d.id().equals(id) && d.deckData() != null) return d.deckData();
                }
            }
        }
        return deck;
    }

    private static void failed(ClientContext c, String reason) {
        c.send("MatchRequestWithSpecificClientFailed", Map.of("failureType", Map.of("id", reason)), 0);
    }

    private synchronized void request(ClientContext c, JsonNode v, int rid, int flags) throws SQLException {
        if (c.account() == null) return;
        JsonNode deck = resolveDeck(c, v == null ? null : v.get("deck"));
        String opponentId = text(v, "opponentID");
        JsonNode options = v != null && v.has("matchOptions") ? v.get("matchOptions") : null;
        LOG.info("[TCP] [{}] Player {} challenging {}", c.remoteAddress(), c.account().username(), opponentId);
        ack(c, rid);

        removeChallengesFor(c, false); // one outstanding challenge per player, in either direction
        ClientContext opponent = c.services().registry().find(opponentId).orElse(null);
        if (opponent == null || opponent == c) {
            failed(c, "Your opponent is no longer online.");
            return;
        }
        if (starter.isInMatch(opponentId)) {
            failed(c, "Your opponent is already in a match.");
            return;
        }
        String path = "challenge_" + UUID.randomUUID();
        pending.put(path, new Pending(c, opponent, deck, options));
        c.send("MatchRequestSent", Map.of("path", path, "opponentID", opponentId), 0);
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("path", path);
        requested.put("opponentID", c.account().accountId());
        requested.put("matchOptions", options == null ? Map.of() : options);
        opponent.send("MatchRequested", requested, 0);
    }

    private synchronized void accept(ClientContext c, JsonNode v, int rid, int flags) throws SQLException {
        if (c.account() == null) return;
        String path = text(v, "path");
        JsonNode deck = resolveDeck(c, v == null ? null : v.get("deck"));
        LOG.info("[TCP] [{}] Player {} accepting challenge {}", c.remoteAddress(), c.account().username(), path);
        ack(c, rid);

        Pending p = pending.get(path);
        if (p == null || p.opponent() != c) {
            failed(c, "This challenge is no longer available.");
            return;
        }
        pending.remove(path);
        ClientContext challenger = p.challenger();
        boolean challengerOnline = challenger.account() != null && c.services().registry()
                .find(challenger.account().accountId()).filter(o -> o == challenger).isPresent();
        if (!challengerOnline) {
            failed(c, "Your opponent is no longer online.");
            return;
        }
        c.send("MatchRequestSent", Map.of("path", path, "opponentID", challenger.account().accountId()), 0);
        starter.start(challenger, p.deck(), c, deck, p.options());
    }

    private synchronized void reject(ClientContext c, JsonNode v, int rid, int flags) {
        String path = text(v, "path");
        LOG.info("[TCP] [{}] Player {} rejecting challenge {}", c.remoteAddress(),
                c.account() == null ? "?" : c.account().username(), path);
        ack(c, rid);
        Pending p = pending.remove(path);
        if (p != null) p.challenger().send("MatchRequestRejected", Map.of("path", path, "failureType", "Rejected"), 0);
    }

    private synchronized void cancel(ClientContext c, JsonNode v, int rid, int flags) {
        String path = text(v, "path");
        ack(c, rid);
        Pending p = pending.remove(path);
        Map<String, Object> cancelled = Map.of("path", path);
        c.send("MatchRequestCancelled", cancelled, 0); // always echo so the client unwinds from Canceling
        if (p != null) {
            for (ClientContext party : List.of(p.challenger(), p.opponent())) {
                if (party != c) party.send("MatchRequestCancelled", cancelled, 0);
            }
        }
    }

    private void removeChallengesFor(ClientContext c, boolean notifySelf) {
        for (String path : new ArrayList<>(pending.keySet())) {
            Pending p = pending.get(path);
            if (p.challenger() != c && p.opponent() != c) continue;
            pending.remove(path);
            Map<String, Object> cancelled = Map.of("path", path);
            for (ClientContext party : List.of(p.challenger(), p.opponent())) {
                if (party == c && !notifySelf) continue;
                party.send("MatchRequestCancelled", cancelled, 0);
            }
        }
    }

    /** Disconnect cleanup: cancels every pending challenge involving the client and tells the other party. */
    public synchronized void clientGone(ClientContext c) {
        removeChallengesFor(c, false);
    }

    /** Number of pending challenges (tests / diagnostics). */
    public synchronized int pendingCount() {
        return pending.size();
    }
}
