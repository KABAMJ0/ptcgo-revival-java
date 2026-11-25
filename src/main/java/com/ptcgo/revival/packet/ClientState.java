package com.ptcgo.revival.packet;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** What this connection has already been sent, so catalogue downloads never repeat an archetype. */
public final class ClientState {
    public final Set<String> sentArchetypes = ConcurrentHashMap.newKeySet();
    public final Set<String> sentArchetypeKeys = ConcurrentHashMap.newKeySet();
    public volatile String lastScene;
}
