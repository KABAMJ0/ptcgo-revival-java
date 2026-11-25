package com.ptcgo.revival.social;

import com.ptcgo.revival.packet.ClientContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Lobby room membership. A member whose connection is gone (no longer in the client registry) drops out automatically. */
public final class ChatRooms {
    private final Map<String, Set<ClientContext>> rooms = new ConcurrentHashMap<>();

    public void join(String roomId, ClientContext c) {
        rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(c);
    }

    public void leave(String roomId, ClientContext c) {
        Set<ClientContext> m = rooms.get(roomId);
        if (m != null) m.remove(c);
    }

    public boolean isMember(String roomId, ClientContext c) {
        Set<ClientContext> m = rooms.get(roomId);
        return m != null && m.contains(c);
    }

    /** Members still logged in; stale ones are pruned. */
    public List<ClientContext> members(String roomId) {
        Set<ClientContext> m = rooms.get(roomId);
        List<ClientContext> out = new ArrayList<>();
        if (m == null) return out;
        for (ClientContext c : m) {
            if (c.account() != null && c.services().registry().find(c.account().accountId()).filter(o -> o == c).isPresent()) {
                out.add(c);
            } else {
                m.remove(c);
            }
        }
        return out;
    }

    /** Disconnect cleanup. */
    public void removeEverywhere(ClientContext c) {
        rooms.values().forEach(m -> m.remove(c));
    }
}
