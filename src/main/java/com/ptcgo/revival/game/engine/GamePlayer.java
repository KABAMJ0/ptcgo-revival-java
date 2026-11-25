package com.ptcgo.revival.game.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.game.wire.Attr;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** One participant of a match: a connected client or the built-in AI (player_abstract.py). */
public abstract class GamePlayer {
    public static final String DEFAULT_COIN = "B9A4EA96-949E-11E1-890F-EFB676C7909C";
    public static final String DEFAULT_SLEEVE = "e079c0d3-b934-4fbd-b021-545106c75693";
    public static final String DEFAULT_DECK_BOX = "e129b0d3-b934-4fbd-b021-545106c75694";
    private static final String EMPTY_GUID = "00000000-0000-0000-0000-000000000000";

    /** An offer awaiting a reply, replayed verbatim on reconnect. */
    public record Offer(String name, Map<String, Object> value, int flags) {}

    public final String accountId;
    public final String username;
    public final JsonNode deckData;
    /** Set while an offer is outstanding; completed by the reply, or exceptionally with GameOver. */
    public volatile CompletableFuture<JsonNode> pendingChoice;
    public volatile Offer pendingOffer;

    protected GamePlayer(String accountId, String username, JsonNode deckData) {
        this.accountId = accountId;
        this.username = username;
        this.deckData = deckData;
    }

    public abstract String screenName();

    public abstract List<String> avatarItems();

    /** Sends one message down to this participant (no-op for the AI and for a detached client). */
    public abstract void send(String name, Map<String, Object> value, int flags);

    public final void send(String name, Map<String, Object> value) {
        send(name, value, 0);
    }

    public boolean isAi() {
        return false;
    }

    private String cosmetic(int attrId, String def) {
        JsonNode attrs = deckData == null ? null : deckData.path("attributes");
        if (attrs != null && attrs.isArray()) {
            for (JsonNode a : attrs) {
                if (a.path("name").asInt(-1) != attrId) continue;
                JsonNode v = a.get("value");
                if (v == null) continue;
                String item = v.isArray() ? (v.size() > 0 ? v.get(0).asText("") : "") : v.isTextual() ? v.asText() : "";
                if (!item.isEmpty() && !item.equals(EMPTY_GUID)) return item;
            }
        }
        return def;
    }

    public String sleeveId() {
        return cosmetic(Attr.SELECTED_SLEEVE, DEFAULT_SLEEVE);
    }

    public String coinId() {
        return cosmetic(Attr.SELECTED_COIN, DEFAULT_COIN);
    }

    public String deckBoxId() {
        return cosmetic(Attr.SELECTED_DECK_BOX, DEFAULT_DECK_BOX);
    }
}
