package com.ptcgo.revival.game.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/** Server-wide card-ban hook (an admin table in the Python server; not ported: nothing is banned). */
@FunctionalInterface
public interface BanPolicy {
    /** The refusal text when the deck holds a banned card, else empty. */
    Optional<String> refusalText(JsonNode deck);

    BanPolicy NONE = deck -> Optional.empty();
}
