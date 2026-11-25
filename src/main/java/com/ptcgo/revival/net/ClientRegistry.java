package com.ptcgo.revival.net;

import com.ptcgo.revival.packet.ClientContext;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Logged-in clients by account id, plus accounts whose login is mid-flight. */
public final class ClientRegistry {
    private final Map<String, ClientContext> byAccount = new ConcurrentHashMap<>();
    private final Set<String> loginsInFlight = ConcurrentHashMap.newKeySet();

    public Optional<ClientContext> find(String accountId) {
        return Optional.ofNullable(byAccount.get(accountId));
    }

    /** Claims the account for a login; false if another login for it is already running. */
    public boolean beginLogin(String accountId) {
        return loginsInFlight.add(accountId);
    }

    public void endLogin(String accountId) {
        loginsInFlight.remove(accountId);
    }

    public void register(String accountId, ClientContext client) {
        byAccount.put(accountId, client);
    }

    public void unregister(String accountId, ClientContext client) {
        byAccount.remove(accountId, client);
    }

    public int onlineCount() {
        return byAccount.size();
    }
}
