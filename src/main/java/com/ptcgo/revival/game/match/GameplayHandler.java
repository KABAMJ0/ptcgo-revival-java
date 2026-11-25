package com.ptcgo.revival.game.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.game.engine.GameSession;
import com.ptcgo.revival.packet.ClientContext;
import com.ptcgo.revival.packet.PacketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static com.ptcgo.revival.game.wire.Msgs.obj;

/**
 * In-game client messages (ptcgo/packets/handlers/gameplay.py): PlayerReady, the selection/choice replies,
 * ReconnectToGame, ResignGame and UpdateUserTimeoutStatus. GameChat is handled by ChatHandler through the
 * {@link MatchManager} chat route.
 */
public final class GameplayHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GameplayHandler.class);
    private final MatchManager manager;

    public GameplayHandler(MatchManager manager) {
        this.manager = manager;
    }

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("PlayerReady", this::playerReady);
        m.put("GameCustomChoice", (c, v, rid, f) -> forward(c, v, "CustomChoice"));
        m.put("GameMulliganChoice", (c, v, rid, f) -> forward(c, v, "MulliganChoice"));
        m.put("SelectionWithTargetsAndActions", (c, v, rid, f) -> forward(c, v, "TargetSelection"));
        m.put("SelectionWithTargets", (c, v, rid, f) -> forward(c, v, "TargetSelection"));
        m.put("ReconnectToGame", this::reconnectToGame);
        m.put("ResignGame", this::resignGame);
        m.put("UpdateUserTimeoutStatus", (c, v, rid, f) -> LOG.debug("[Gameplay] {} updated user timeout status: {}",
                c.account() == null ? "?" : c.account().username(), v));
        return m;
    }

    /** Routes a player's reply into their active GameSession. */
    private void forward(ClientContext c, JsonNode message, String label) {
        if (c.account() == null) return;
        String id = c.account().accountId();
        LOG.debug("[Gameplay] Player {} made {}: {}", c.account().username(), label, message);
        GameSession s = manager.sessionByPlayer(id);
        if (s != null) s.receivePlayerAction(id, message, c);
    }

    private void playerReady(ClientContext c, JsonNode v, int rid, int flags) {
        if (c.account() == null) return;
        String id = c.account().accountId();
        LOG.info("[Gameplay] Player {} ({}) sends PlayerReady", c.account().username(), id);
        GameSession s = manager.sessionByPlayer(id);
        if (s != null) s.markPlayerReady(id, c);
        else LOG.warn("[Gameplay] PlayerReady received but player {} has no active GameSession", id);
    }

    private void reconnectToGame(ClientContext c, JsonNode v, int rid, int flags) {
        if (c.account() == null) return;
        String id = c.account().accountId();
        String gameId = v != null && v.hasNonNull("gameID") ? v.get("gameID").asText() : null;
        GameSession s = manager.sessionByPlayer(id);
        if (s == null || s.isOver() || (gameId != null && !s.gameId.equals(gameId))) {
            LOG.warn("[Gameplay] ReconnectToGame for {}: no matching active session", id);
            c.send("PlayerNotInGame", obj(), 0);
            return;
        }
        s.reconnectPlayer(c, id);
    }

    private void resignGame(ClientContext c, JsonNode v, int rid, int flags) {
        if (c.account() == null) return;
        String id = c.account().accountId();
        LOG.info("[Gameplay] Player {} Conceded/Resigned Match", c.account().username());
        GameSession s = manager.sessionByPlayer(id);
        // Full EOG flow (GameCompletedMessage to both viewers, rewards, ladder points); the session removes itself
        // when its loop unwinds.
        if (s != null) s.concede(id);
        else manager.removeSessionByPlayer(id);
    }
}
