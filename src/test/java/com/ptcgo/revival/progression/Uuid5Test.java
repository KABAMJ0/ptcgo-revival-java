package com.ptcgo.revival.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class Uuid5Test {
    @Test
    void matchesPythonUuid5() {
        // python3: uuid.uuid5(uuid.NAMESPACE_URL, 'spirit:daily:acct-1:2026-11-25:first_win')
        assertEquals(UUID.fromString("d738b8fb-1b58-5081-8da5-f5d76e67e52e"),
                Uuid5.of(Uuid5.NAMESPACE_URL, "spirit:daily:acct-1:2026-11-25:first_win"));
    }
}
