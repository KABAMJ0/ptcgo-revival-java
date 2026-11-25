package com.ptcgo.revival.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.game.engine.GamePlayer;
import com.ptcgo.revival.net.WargCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A headless participant that decides like the AI and records every message the engine sends it. */
public final class RecordingPlayer extends GamePlayer {
    public record Sent(String name, JsonNode value, int flags) {}

    public final List<Sent> sent = java.util.Collections.synchronizedList(new ArrayList<>());

    public RecordingPlayer(String id, String name, JsonNode deck) {
        super(id, name, deck);
    }

    @Override
    public String screenName() {
        return username;
    }

    @Override
    public List<String> avatarItems() {
        return List.of("00000000-0000-0000-0000-000000000a00");
    }

    @Override
    public void send(String name, Map<String, Object> value, int flags) {
        sent.add(new Sent(name, WargCodec.JSON.valueToTree(value), flags));
    }

    @Override
    public boolean isAi() {
        return true;
    }
}
