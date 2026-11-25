package com.ptcgo.revival.deck;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Decides which formats a deck is legal in (format names as the client knows them). */
public interface DeckValidator {
    /**
     * @param deck  the deck as the client sends/stores it ({"deckID","deckName","piles",...})
     * @param owned lower-case archetype guid to owned copies
     * @return names of the formats the deck is valid in, e.g. ["Modified","Unlimited"]
     */
    List<String> validFormats(JsonNode deck, Map<String, Integer> owned);

    /** Placeholder until the real rules are ported: every deck is only Unlimited-legal. */
    DeckValidator PERMISSIVE = (deck, owned) -> List.of("Unlimited");
}
