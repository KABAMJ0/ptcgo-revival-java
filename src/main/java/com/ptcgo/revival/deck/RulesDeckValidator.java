package com.ptcgo.revival.deck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;
import com.ptcgo.revival.deck.DeckValidationRow.Failure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deck-building rules (size, copy limits, rule-box limits, ownership) plus per-format legality. Stateless per call
 * and safe to share between connections.
 */
public final class RulesDeckValidator implements DeckValidator {
    private static final Logger LOG = LoggerFactory.getLogger(RulesDeckValidator.class);

    public static final int DECK_SIZE = 60;
    public static final int MAX_COPIES = 4;
    public static final int MAX_ACE_SPEC = 1;

    public static final int ATTR_IS_THEME_DECK = 201290;
    public static final int ATTR_THEME_DECK_NAME = 10800;

    private static final int ATTR_NAME = 10140;
    private static final int ATTR_CARD_TYPE = 200300;
    private static final int ATTR_STAGE = 200540;
    private static final int CARD_TYPE_POKEMON = 0;
    private static final int CARD_TYPE_LEGEND_HALF = 1;
    private static final int STAGE_BASIC = 0;
    private static final int STAGE_VUNION = 8;
    private static final String ACE_SPEC = "ACE SPEC";

    /** Printed rule-box limits; the client draws these but refuses nothing, so the server must. */
    private static final Map<String, String> ONE_PER_DECK = Map.of(
            ACE_SPEC, "A deck can't contain more than 1 ACE SPEC card.",
            "Radiant", "A deck can't contain more than 1 Radiant Pokémon.");
    private static final List<String> ONE_PER_DECK_ORDER = List.of(ACE_SPEC, "Radiant");
    private static final String PRISM_STAR = "Prism Star";
    private static final String PRISM_STAR_MESSAGE = "A deck can't contain more than 1 Prism Star card with the same name.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Catalog catalog;
    private final FormatManager formats;
    private final ThemeDecks themeDecks;

    public RulesDeckValidator(Catalog catalog, FormatManager formats, ThemeDecks themeDecks) {
        this.catalog = catalog;
        this.formats = formats;
        this.themeDecks = themeDecks;
    }

    public FormatManager formats() {
        return formats;
    }

    /** Names of the formats the deck fully passes (wire names, the deck attribute 10860 payload). */
    @Override
    public List<String> validFormats(JsonNode deck, Map<String, Integer> owned) {
        return validate(deck, owned, formats.playFormatGuids()).stream()
                .filter(DeckValidationRow::valid).map(DeckValidationRow::formatName).toList();
    }

    /** One row per format the server plays, in configured order. */
    public List<DeckValidationRow> validate(JsonNode deck, Map<String, Integer> owned) {
        return validate(deck, owned, formats.playFormatGuids());
    }

    /**
     * One row per requested format guid (what ValidateDecks answers with); an empty request means the client's
     * default list. {@code owned} (lower-case guid to copies) may be null to skip the ownership check.
     */
    public List<DeckValidationRow> validate(JsonNode deck, Map<String, Integer> owned, List<String> formatGuids) {
        Evaluation e = new Evaluation(deck, owned);
        List<String> requested = formatGuids == null || formatGuids.isEmpty()
                ? FormatManager.DEFAULT_VALIDATION_FORMATS : formatGuids;
        List<DeckValidationRow> rows = new ArrayList<>();
        for (String requestedGuid : requested) rows.add(e.row(requestedGuid.toLowerCase(Locale.ROOT)));
        List<String> invalid = rows.stream().filter(r -> !r.valid()).map(DeckValidationRow::formatName).toList();
        LOG.info("[Rules] Validated deck '{}' ({}): {}", e.deckName, e.deckId,
                invalid.isEmpty() ? "all formats OK" : "invalid in " + String.join(", ", invalid));
        return rows;
    }

    /** Deck-building errors without format or theme restrictions. */
    public List<Failure> baseFailures(JsonNode deck, Map<String, Integer> owned) {
        return new Evaluation(deck, owned).baseFailures();
    }

    /**
     * Sets IS_THEME_DECK on the deck's attribute list, adding THEME_DECK_NAME (the deck name) when flagged.
     * Attribute entries are {"name": attrId, "value": ...}.
     */
    public static void setThemeDeckAttributes(ObjectNode deck, boolean isTheme) {
        ArrayNode kept = JsonNodeFactory.instance.arrayNode();
        boolean hasName = false;
        for (JsonNode a : deck.path("attributes")) {
            int id = a.path("name").asInt(-1);
            if (id == ATTR_IS_THEME_DECK) continue;
            if (id == ATTR_THEME_DECK_NAME) hasName = true;
            kept.add(a);
        }
        ObjectNode flag = kept.addObject();
        flag.put("name", ATTR_IS_THEME_DECK);
        flag.put("value", isTheme);
        if (isTheme && !hasName) {
            ObjectNode name = kept.addObject();
            name.put("name", ATTR_THEME_DECK_NAME);
            name.putObject("value").put("id", deck.path("deckName").asText(""));
        }
        deck.set("attributes", kept);
    }

    /** Display name, falling back to the NAME attribute's localisation id, then the guid. */
    static String displayName(Card card) {
        if (!card.displayName().isEmpty()) return card.displayName();
        String name = card.stringAttribute(ATTR_NAME);
        if (name == null || name.isEmpty()) return card.guid();
        if (name.startsWith("{")) {
            try {
                String id = JSON.readTree(name).path("id").asText("");
                return id.isEmpty() ? card.guid() : id;
            } catch (java.io.IOException ex) {
                return name;
            }
        }
        return name;
    }

    /** Cards sharing a name share the 4-copy limit, except V-UNION pieces, which count per piece. */
    static String copyLimitKey(Card card) {
        String name = displayName(card);
        if (card.intAttribute(ATTR_CARD_TYPE, -1) == CARD_TYPE_LEGEND_HALF
                && card.intAttribute(ATTR_STAGE, -1) == STAGE_VUNION) {
            return name + " #" + card.collectorNumber();
        }
        return name;
    }

    private static boolean isBasicPokemon(Card card) {
        return card.intAttribute(ATTR_CARD_TYPE, -1) == CARD_TYPE_POKEMON
                && card.intAttribute(ATTR_STAGE, STAGE_BASIC) == STAGE_BASIC;
    }

    private static Failure failure(String type, String text, Set<String> offenders) {
        return new Failure(type, text, List.copyOf(offenders));
    }

    private static Failure failure(String type, String text) {
        return new Failure(type, text, List.of());
    }

    /** One deck being checked; the format-independent failures are computed once. */
    private final class Evaluation {
        final String deckId;
        final String deckName;
        final List<String> guids = new ArrayList<>();
        final List<Card> cards = new ArrayList<>();
        final Set<String> unknown = new TreeSet<>();
        final Map<String, Integer> owned;
        private List<Failure> base;

        Evaluation(JsonNode deck, Map<String, Integer> owned) {
            this.owned = owned;
            JsonNode id = deck.path("deckID");
            this.deckId = id.isMissingNode() || id.isNull() ? null : id.asText();
            this.deckName = deck.path("deckName").asText("Unknown Deck");
            JsonNode piles = deck.path("piles");
            JsonNode pile = piles.path("deck");
            if (!pile.isArray() || pile.isEmpty()) pile = piles.path("CakePile");
            for (JsonNode g : pile) {
                String guid = g.asText("").toLowerCase(Locale.ROOT);
                guids.add(guid);
                Card card = catalog.card(guid);
                if (card == null) unknown.add(guid); else cards.add(card);
            }
        }

        DeckValidationRow row(String formatGuid) {
            List<Failure> details = new ArrayList<>(baseFailures());
            GameFormat fmt = formats.byGuid(formatGuid);
            if (fmt == null) {
                details.add(failure("BoolRestriction",
                        "The " + formats.formatName(formatGuid) + " format is not available on this server."));
            } else {
                Set<String> offenders = new TreeSet<>();
                for (Card c : cards) if (!formats.isCardLegal(formatGuid, c)) offenders.add(c.guid());
                if (!offenders.isEmpty()) {
                    details.add(failure("DeckContainsBannedCards",
                            "This deck contains cards that are not legal in the " + fmt.formatName() + " format.", offenders));
                }
                if (formatGuid.equals(FormatManager.THEME) && !themeDecks.matches(guids)) {
                    details.add(failure("BoolRestriction", "Theme format requires the exact contents of an approved theme deck."));
                }
            }
            return new DeckValidationRow(deckId, formatGuid, formats.formatName(formatGuid), details.isEmpty(), details);
        }

        List<Failure> baseFailures() {
            if (base == null) base = List.copyOf(computeBase());
            return base;
        }

        private List<Failure> computeBase() {
            List<Failure> out = new ArrayList<>();
            if (guids.size() != DECK_SIZE) {
                out.add(failure("ExactSize", "A deck must contain exactly " + DECK_SIZE
                        + " cards (this deck has " + guids.size() + ")."));
            }
            if (!unknown.isEmpty()) {
                out.add(failure("MustNotContain", "This deck contains cards unknown to the server.", unknown));
            }

            Map<String, List<String>> byName = new TreeMap<>();
            for (Card c : cards) {
                if (!FormatManager.isBasicEnergy(c)) byName.computeIfAbsent(copyLimitKey(c), k -> new ArrayList<>()).add(c.guid());
            }
            byName.values().removeIf(g -> g.size() <= MAX_COPIES);
            if (!byName.isEmpty()) {
                Set<String> offenders = new TreeSet<>();
                byName.values().forEach(offenders::addAll);
                out.add(failure("MaxDuplicates", "A deck can't contain more than " + MAX_COPIES
                        + " cards with the same name: " + String.join(", ", byName.keySet()) + ".", offenders));
            }

            for (String subtype : ONE_PER_DECK_ORDER) {
                List<Card> holders = cards.stream().filter(c -> c.hasSubtypeIgnoreCase(subtype)).toList();
                if (holders.size() > 1) {
                    out.add(failure("MaxMatches", ONE_PER_DECK.get(subtype) + " This deck has " + holders.size() + ".",
                            guidSet(holders)));
                }
            }

            Map<String, List<String>> prism = new TreeMap<>();
            for (Card c : cards) {
                if (c.hasSubtypeIgnoreCase(PRISM_STAR)) prism.computeIfAbsent(copyLimitKey(c), k -> new ArrayList<>()).add(c.guid());
            }
            prism.values().removeIf(g -> g.size() <= 1);
            if (!prism.isEmpty()) {
                Set<String> offenders = new TreeSet<>();
                prism.values().forEach(offenders::addAll);
                out.add(failure("MaxMatches", PRISM_STAR_MESSAGE + " Repeated: " + String.join(", ", prism.keySet()) + ".", offenders));
            }

            // Same flag as the ACE SPEC rule-box check above, matched exactly: reported separately like the Python server.
            List<Card> aceSpecs = cards.stream().filter(c -> c.subtypes().contains(ACE_SPEC)).toList();
            if (aceSpecs.size() > MAX_ACE_SPEC) {
                out.add(failure("MaxDuplicates", "A deck can't contain more than " + MAX_ACE_SPEC
                        + " ACE SPEC card (this deck has " + aceSpecs.size() + ").", guidSet(aceSpecs)));
            }

            if (cards.stream().noneMatch(RulesDeckValidator::isBasicPokemon)) {
                out.add(failure("MustContain", "A deck must contain at least 1 Basic Pokémon."));
            }

            if (owned != null) {
                Map<String, Integer> counts = new TreeMap<>();
                for (Card c : cards) if (!FormatManager.isBasicEnergy(c)) counts.merge(c.guid(), 1, Integer::sum);
                Set<String> unowned = new TreeSet<>();
                counts.forEach((guid, n) -> { if (owned.getOrDefault(guid, 0) < n) unowned.add(guid); });
                if (!unowned.isEmpty()) {
                    out.add(failure("UnownedCards", "This deck contains cards you don't own.", unowned));
                }
            }
            return out;
        }

        private static Set<String> guidSet(List<Card> cs) {
            Set<String> s = new TreeSet<>();
            cs.forEach(c -> s.add(c.guid()));
            return s;
        }
    }
}
