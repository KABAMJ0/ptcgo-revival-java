package com.ptcgo.revival.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CasTicketsTest {
    @Test
    void singleUseAndUserBound() {
        CasTickets t = new CasTickets();
        t.issue("ST-6f1c2a9e-1111-4222-8333-444455556666", "Ash");
        assertFalse(t.consume("ST-6f1c2a9e-1111-4222-8333-444455556666", "Misty"));
        assertTrue(t.consume("ST-6f1c2a9e-1111-4222-8333-444455556666", " ash "));
        assertFalse(t.consume("ST-6f1c2a9e-1111-4222-8333-444455556666", "Ash"));
    }

    @Test
    void toleratesClientDroppingTheLeadingS() {
        CasTickets t = new CasTickets();
        t.issue("ST-6f1c2a9e-1111-4222-8333-444455556666", "Ash");
        assertTrue(t.consume("T-6f1c2a9e-1111-4222-8333-444455556666", "Ash"));
    }

    @Test
    void expires() {
        AtomicLong now = new AtomicLong();
        CasTickets t = new CasTickets(now::get);
        t.issue("ST-6f1c2a9e-1111-4222-8333-444455556666", "Ash");
        now.addAndGet(CasTickets.TTL_NANOS + 1);
        assertFalse(t.consume("ST-6f1c2a9e-1111-4222-8333-444455556666", "Ash"));
    }
}
