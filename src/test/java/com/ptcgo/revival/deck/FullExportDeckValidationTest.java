package com.ptcgo.revival.deck;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class FullExportDeckValidationTest {
    /** Opt-in: mvn test -Dcatalog.dir=/path/to/export (the full export with formats.json and products.json). */
    @Test
    @EnabledIfSystemProperty(named = "catalog.dir", matches = ".+")
    void validatesAgainstRealCatalogueAndFormats() throws Exception {
        Path dir = Path.of(System.getProperty("catalog.dir"));
        Catalog catalog = Catalog.load(dir);
        ThemeDecks themes = ThemeDecks.load(dir);
        FormatManager fm = FormatManager.load(dir, catalog, themes, g -> false, System::currentTimeMillis);
        assertEquals(6, fm.formats().size());
        RulesDeckValidator v = new RulesDeckValidator(catalog, fm, themes);

        // 15 distinct plain Basic Pokemon from SV5, four copies each.
        List<Card> picks = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String guid : catalog.guidsInSet("SV5")) {
            Card c = catalog.card(guid);
            if (c.kind().equals("pokemon") && c.subtypes().equals(List.of("Basic")) && names.add(c.displayName())) picks.add(c);
            if (picks.size() == 15) break;
        }
        assertEquals(15, picks.size());
        ObjectMapper json = new ObjectMapper();
        ObjectNode deck = json.createObjectNode();
        deck.put("deckID", "d").put("deckName", "SV5 basics");
        ArrayNode pile = deck.putObject("piles").putArray("deck");
        Map<String, Integer> owned = new HashMap<>();
        for (Card c : picks) {
            for (int i = 0; i < 4; i++) pile.add(c.guid());
            owned.put(c.guid(), 4);
        }
        assertEquals(List.of("Modified", "Expanded", "Unlimited", "TrainerChallenge"), v.validFormats(deck, owned));

        // The same deck with an old BW card swapped in is no longer Modified.
        String bw = catalog.guidsInSet("BW1").iterator().next();
        pile.set(0, json.getNodeFactory().textNode(bw));
        owned.put(bw, 4);
        assertFalse(v.validFormats(deck, owned).contains("Modified"));
        assertTrue(v.validFormats(deck, owned).contains("Unlimited"));
    }
}
