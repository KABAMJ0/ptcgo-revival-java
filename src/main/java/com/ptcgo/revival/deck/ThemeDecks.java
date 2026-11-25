package com.ptcgo.revival.deck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Approved theme decks: the Theme format accepts exactly the contents (quantities included) of a theme deck product
 * flagged {@code is_theme_deck} and {@code theme_legal}, and only those cards are legal in it.
 */
public final class ThemeDecks {
    private static final Logger LOG = LoggerFactory.getLogger(ThemeDecks.class);
    private static final ThemeDecks NONE = new ThemeDecks(List.of());

    private final Set<Map<String, Integer>> signatures = new HashSet<>();
    private final List<List<String>> decks = new ArrayList<>();
    private final Set<String> legalCards = new HashSet<>();

    /** @param approvedContents the card guid list of each approved theme deck */
    public ThemeDecks(Collection<? extends List<String>> approvedContents) {
        for (List<String> contents : approvedContents) {
            decks.add(List.copyOf(contents));
            signatures.add(signature(contents));
            for (String g : contents) legalCards.add(g.toLowerCase(Locale.ROOT));
        }
    }

    public static ThemeDecks none() {
        return NONE;
    }

    /** Reads {@code <dir>/products.json}; a missing file means there are no theme decks. */
    public static ThemeDecks load(Path dir) throws IOException {
        Path file = dir.resolve("products.json");
        if (!Files.isRegularFile(file)) {
            LOG.warn("[ThemeDecks] {} not found; the Theme format will accept nothing", file);
            return NONE;
        }
        List<List<String>> approved = new ArrayList<>();
        for (JsonNode product : new ObjectMapper().readTree(file.toFile())) {
            JsonNode deck = product.path("deck");
            if (!deck.path("is_theme_deck").asBoolean(false) || !deck.path("theme_legal").asBoolean(false)) continue;
            List<String> contents = new ArrayList<>();
            deck.path("contents").forEach(g -> contents.add(g.asText()));
            if (!contents.isEmpty()) approved.add(contents);
        }
        LOG.info("[ThemeDecks] {} approved theme decks", approved.size());
        return new ThemeDecks(approved);
    }

    /** The card guid list of every approved theme deck, in product order. */
    public List<List<String>> decks() {
        return List.copyOf(decks);
    }

    public boolean isLegalCard(String guid) {
        return legalCards.contains(guid.toLowerCase(Locale.ROOT));
    }

    /** True when the card list is exactly the contents of an approved theme deck. */
    public boolean matches(List<String> cardGuids) {
        return signatures.contains(signature(cardGuids));
    }

    private static Map<String, Integer> signature(List<String> guids) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String g : guids) counts.merge(g.toLowerCase(Locale.ROOT), 1, Integer::sum);
        return counts;
    }
}
