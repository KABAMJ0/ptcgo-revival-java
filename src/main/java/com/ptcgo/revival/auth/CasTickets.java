package com.ptcgo.revival.auth;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Single-use CAS service tickets issued by the HTTP login page and consumed on the game socket.
 * Tickets are compared by their last five dash-separated groups: the client is observed to present
 * {@code ST-<uuid>} as {@code T-<uuid>}, and the UUID part alone carries the 122 random bits.
 */
public final class CasTickets {
    static final long TTL_NANOS = 600L * 1_000_000_000L;

    private record Entry(String ticket, String username, long issuedAt) {}

    private final Map<String, Entry> pending = new LinkedHashMap<>();
    private final java.util.function.LongSupplier clock;

    public CasTickets() {
        this(System::nanoTime);
    }

    CasTickets(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void issue(String ticket, String username) {
        pending.put(key(ticket), new Entry(ticket, username, clock.getAsLong()));
    }

    /** Validates and removes a ticket for this user; false if unknown, used, expired or for someone else. */
    public synchronized boolean consume(String ticket, String username) {
        sweep();
        Entry e = pending.get(key(ticket));
        if (e == null || !e.username().strip().equalsIgnoreCase(username == null ? "" : username.strip())) {
            return false;
        }
        pending.remove(key(ticket));
        return true;
    }

    public synchronized void sweep() {
        long now = clock.getAsLong();
        for (Iterator<Entry> it = pending.values().iterator(); it.hasNext(); ) {
            if (now - it.next().issuedAt() > TTL_NANOS) it.remove();
        }
    }

    static String key(String ticket) {
        String t = ticket == null ? "" : ticket.strip();
        String[] parts = t.split("-", -1);
        if (parts.length < 5) return t.toLowerCase(Locale.ROOT);
        return String.join("-", java.util.Arrays.copyOfRange(parts, parts.length - 5, parts.length))
                .toLowerCase(Locale.ROOT);
    }
}
