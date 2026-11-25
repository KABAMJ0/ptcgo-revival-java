package com.ptcgo.revival.game.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.packet.ClientContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** A player on a live client connection; the client can detach (disconnect) and re-attach (reconnect). */
public final class NetworkPlayer extends GamePlayer {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkPlayer.class);

    private volatile ClientContext client;
    private final String screenName;
    private final List<String> avatar;

    public NetworkPlayer(ClientContext client, JsonNode deckData, List<String> avatarItems) {
        super(client.account().accountId(), client.account().username(), deckData);
        this.client = client;
        this.screenName = client.account().displayName();
        this.avatar = avatarItems;
    }

    public ClientContext client() {
        return client;
    }

    public void bind(ClientContext c) {
        this.client = c;
    }

    /** True while a live socket is bound; false after a disconnect detach. */
    public boolean connected() {
        return client != null;
    }

    @Override
    public String screenName() {
        return screenName;
    }

    @Override
    public List<String> avatarItems() {
        return avatar;
    }

    @Override
    public void send(String name, Map<String, Object> value, int flags) {
        // A detached player drops sends until they reconnect: the full board is re-serialized on reconnect.
        ClientContext c = client;
        if (c == null) return;
        try {
            c.send(name, value, 0, flags);
        } catch (RuntimeException e) {
            LOG.error("[Session] Failed to send packet to {}: {}", username, e.toString());
        }
    }
}
