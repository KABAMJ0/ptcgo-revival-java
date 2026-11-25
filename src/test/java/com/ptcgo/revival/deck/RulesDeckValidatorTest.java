package com.ptcgo.revival.deck;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RulesDeckValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STANDARD_SET = "SV5";
    private static final String OLD_SET = "XY1";

    private final Set<String> serverBans = new HashSet<>();
    private final List<Card> all = new ArrayList<>();
    private final List<Card> trainers = new ArrayList<>();
    private Card pikachu;
    private Card fireEnergy;
    private Card oldBasic;
    private Card aceOne;
    private Card aceTwo;
    private Catalog catalog;
    private RulesDeckValidator validator;

    private Card card(String guid, String name, String set, int collector, int cardType, Integer stage,
                      boolean specialEnergy, String... subtypes) {
        ObjectNode wire = JSON.createObjectNode();
        wire.putObject("10020").put("type", "string").put("value", set);
        wire.putObject("200300").put("type", "int").put("value", cardType);
        if (stage != null) wire.putObject("200540").put("type", "int").put("value", stage);
        if (specialEnergy) wire.putObject("200970").put("type", "bool").put("value", true);
        Card c = new Card(guid, set, name, set, collector, "x", List.of(subtypes), List.of(), wire);
        all.add(c);
        return c;
    }

    @BeforeEach
    void setUp() {
        pikachu = card("p1", "Pikachu", STANDARD_SET, 1, 0, 0, false, "Basic");
        fireEnergy = card("e1", "Fire Energy", "Free_Energy", 1, 3, null, false);
        oldBasic = card("o1", "Old Mon", OLD_SET, 1, 0, 0, false, "Basic");
        aceOne = card("a1", "Ace One", STANDARD_SET, 2, 2, null, false, "ACE SPEC", "Item");
        aceTwo = card("a2", "Ace Two", STANDARD_SET, 3, 2, null, false, "ACE SPEC", "Item");
        for (int i = 0; i < 10; i++) {
            trainers.add(card("t" + i, "Trainer " + i, STANDARD_SET, 10 + i, 2, null, false, "Item"));
        }
        rebuild();
    }

    /** Rebuilds the validator over the cards created so far (tests add cards after setUp). */
    private void rebuild() {
        catalog = Catalog.of(all);
        List<GameFormat> formats = List.of(
                GameFormat.of("Standard", FormatManager.STANDARD, "Modified", Set.of(STANDARD_SET, "Free_Energy"), false),
                GameFormat.of("Expanded", FormatManager.EXPANDED, "Expanded", Set.of(STANDARD_SET, OLD_SET, "Free_Energy"), false),
                GameFormat.of("Unlimited", FormatManager.UNLIMITED, "Unlimited", Set.of(), true));
        FormatManager fm = new FormatManager(formats, catalog, ThemeDecks.none(), serverBans::contains, () -> 0L);
        validator = new RulesDeckValidator(catalog, fm, ThemeDecks.none());
    }

    private static ObjectNode deck(Object... cardAndCount) {
        ObjectNode d = JSON.createObjectNode();
        d.put("deckID", "deck-1").put("deckName", "Test");
        ArrayNode pile = d.putObject("piles").putArray("deck");
        for (int i = 0; i < cardAndCount.length; i += 2) {
            for (int n = 0; n < (Integer) cardAndCount[i + 1]; n++) pile.add(((Card) cardAndCount[i]).guid());
        }
        return d;
    }

    /** 4 Pikachu + 4 each of trainers 0..7 + 24 basic energy = 60. */
    private ObjectNode legalDeck() {
        List<Object> parts = new ArrayList<>(List.of(pikachu, 4));
        for (int i = 0; i < 8; i++) parts.addAll(List.of(trainers.get(i), 4));
        parts.addAll(List.of(fireEnergy, 24));
        return deck(parts.toArray());
    }

    private Map<String, Integer> ownedAll() {
        Map<String, Integer> owned = new HashMap<>();
        for (Card c : all) owned.put(c.guid(), 4);
        return owned;
    }

    private static DeckValidationRow row(List<DeckValidationRow> rows, String formatGuid) {
        return rows.stream().filter(r -> r.format().equals(formatGuid)).findFirst().orElseThrow();
    }

    private static List<String> types(List<DeckValidationRow.Failure> failures) {
        return failures.stream().map(DeckValidationRow.Failure::failureType).toList();
    }

    @Test
    void legalDeckIsValidInEveryConfiguredFormat() {
        List<DeckValidationRow> rows = validator.validate(legalDeck(), ownedAll());
        assertEquals(5, rows.size()); // three configured + Theme + TrainerChallenge
        assertTrue(row(rows, FormatManager.STANDARD).valid());
        assertTrue(row(rows, FormatManager.EXPANDED).valid());
        assertTrue(row(rows, FormatManager.UNLIMITED).valid());
        assertTrue(row(rows, FormatManager.TRAINER_CHALLENGE).valid());
        DeckValidationRow theme = row(rows, FormatManager.THEME);
        assertFalse(theme.valid()); // not an approved theme deck
        assertEquals("ThemeDeck", theme.formatName());
        assertEquals(List.of("Modified", "Expanded", "Unlimited", "TrainerChallenge"),
                validator.validFormats(legalDeck(), ownedAll()));
    }

    @Test
    void deckWithSixtyOneCardsFailsExactSize() {
        ObjectNode d = legalDeck();
        ((ArrayNode) d.path("piles").path("deck")).add(fireEnergy.guid());
        DeckValidationRow r = row(validator.validate(d, ownedAll(), List.of(FormatManager.UNLIMITED)), FormatManager.UNLIMITED);
        assertFalse(r.valid());
        assertEquals(List.of("ExactSize"), types(r.failures()));
        assertTrue(r.failures().get(0).explanation().contains("this deck has 61"));
    }

    @Test
    void fiveCopiesFailsMaxDuplicates() {
        Map<String, Integer> owned = ownedAll();
        owned.put(pikachu.guid(), 5);
        JsonNode d = deck(pikachu, 5, trainers.get(0), 4, trainers.get(1), 4, trainers.get(2), 4, trainers.get(3), 4,
                trainers.get(4), 4, trainers.get(5), 4, trainers.get(6), 4, fireEnergy, 27);
        DeckValidationRow r = row(validator.validate(d, owned, List.of(FormatManager.STANDARD)), FormatManager.STANDARD);
        assertEquals(List.of("MaxDuplicates"), types(r.failures()), () -> r.failures().toString());
        assertTrue(r.failures().get(0).explanation().endsWith("Pikachu."));
        assertEquals(List.of("p1"), r.failures().get(0).offendingArchetypeIds());
    }

    @Test
    void basicEnergyIsExemptFromCopyLimitAndOwnership() {
        // 24 energy copies in the deck and the owned map says nothing about energy.
        Map<String, Integer> owned = ownedAll();
        owned.remove(fireEnergy.guid());
        assertTrue(validator.baseFailures(legalDeck(), owned).isEmpty());
    }

    @Test
    void specialEnergyIsNotExempt() {
        Card special = card("e2", "Double Energy", STANDARD_SET, 90, 3, null, true);
        rebuild();
        List<String> types = types(validator.baseFailures(deck(pikachu, 4, special, 5), null));
        assertTrue(types.contains("MaxDuplicates"));
    }

    @Test
    void twoAceSpecsAreRejected() {
        JsonNode d = deck(pikachu, 4, aceOne, 1, aceTwo, 1, trainers.get(0), 4, trainers.get(1), 4, trainers.get(2), 4,
                trainers.get(3), 4, trainers.get(4), 4, trainers.get(5), 4, trainers.get(6), 4, fireEnergy, 26);
        DeckValidationRow r = row(validator.validate(d, ownedAll(), List.of(FormatManager.UNLIMITED)), FormatManager.UNLIMITED);
        assertFalse(r.valid());
        assertEquals(List.of("MaxMatches", "MaxDuplicates"), types(r.failures()));
        assertEquals(List.of("a1", "a2"), r.failures().get(1).offendingArchetypeIds());
        assertTrue(r.failures().get(1).explanation().contains("this deck has 2"));
    }

    @Test
    void setNotLegalInStandardIsLegalInUnlimited() {
        JsonNode d = deck(oldBasic, 4, trainers.get(0), 4, trainers.get(1), 4, trainers.get(2), 4, trainers.get(3), 4,
                trainers.get(4), 4, trainers.get(5), 4, trainers.get(6), 4, fireEnergy, 28);
        List<DeckValidationRow> rows = validator.validate(d, ownedAll());
        DeckValidationRow standard = row(rows, FormatManager.STANDARD);
        assertFalse(standard.valid());
        assertEquals(List.of("DeckContainsBannedCards"), types(standard.failures()));
        assertEquals(List.of("o1"), standard.failures().get(0).offendingArchetypeIds());
        assertTrue(row(rows, FormatManager.EXPANDED).valid());
        assertTrue(row(rows, FormatManager.UNLIMITED).valid());
    }

    @Test
    void unownedCardsAreReported() {
        Map<String, Integer> owned = ownedAll();
        owned.put(pikachu.guid(), 3);
        owned.remove(trainers.get(1).guid());
        DeckValidationRow r = row(validator.validate(legalDeck(), owned, List.of(FormatManager.UNLIMITED)), FormatManager.UNLIMITED);
        assertEquals(List.of("UnownedCards"), types(r.failures()));
        assertEquals(List.of("p1", "t1"), r.failures().get(0).offendingArchetypeIds());
        assertTrue(validator.baseFailures(legalDeck(), null).isEmpty()); // null owned map skips the check
    }

    @Test
    void serverBannedCardFailsEveryFormat() {
        serverBans.add(trainers.get(3).guid());
        List<DeckValidationRow> rows = validator.validate(legalDeck(), ownedAll());
        for (String fmt : List.of(FormatManager.STANDARD, FormatManager.EXPANDED, FormatManager.UNLIMITED)) {
            DeckValidationRow r = row(rows, fmt);
            assertEquals(List.of("DeckContainsBannedCards"), types(r.failures()), fmt);
            assertEquals(List.of("t3"), r.failures().get(0).offendingArchetypeIds());
        }
    }

    @Test
    void formatMetadataBansExemptionsAndTimeGates() {
        GameFormat standard = new GameFormat("Standard", FormatManager.STANDARD, "Modified",
                Set.of(STANDARD_SET, "Free_Energy"), false, List.of("t2", "XY1/1"), List.of("XY1/1", "t5"), Map.of());
        GameFormat gated = new GameFormat("Expanded", FormatManager.EXPANDED, "Expanded",
                Set.of(STANDARD_SET, "Free_Energy"), false, List.of(), List.of(), Map.of(STANDARD_SET, 5_000L));
        long[] now = {1_000L};
        FormatManager fm = new FormatManager(List.of(standard, gated), catalog, ThemeDecks.none(), g -> false, () -> now[0]);
        assertFalse(fm.isCardLegal(FormatManager.STANDARD, catalog.card("t2"))); // banned by guid
        assertEquals(Set.of("t2"), fm.bannedGuids(FormatManager.STANDARD)); // "XY1/1" resolved to o1, then exempted
        assertTrue(fm.isCardLegal(FormatManager.STANDARD, catalog.card("t5")));
        assertTrue(fm.isCardEventuallyLegal(FormatManager.EXPANDED, pikachu));
        assertFalse(fm.isCardLegal(FormatManager.EXPANDED, pikachu)); // legalFrom is in the future
        assertTrue(fm.isCardLegal(FormatManager.EXPANDED, fireEnergy)); // no gate on Free_Energy
        now[0] = 5_000L;
        assertTrue(fm.isCardLegal(FormatManager.EXPANDED, pikachu));
    }

    @Test
    void themeFormatNeedsExactApprovedDeck() {
        ObjectNode d = legalDeck();
        List<String> guids = new ArrayList<>();
        d.path("piles").path("deck").forEach(g -> guids.add(g.asText()));
        ThemeDecks themes = new ThemeDecks(List.of(guids));
        FormatManager fm = new FormatManager(validator.formats().formats(), catalog, themes, g -> false, () -> 0L);
        RulesDeckValidator v = new RulesDeckValidator(catalog, fm, themes);
        assertTrue(row(v.validate(d, ownedAll()), FormatManager.THEME).valid());
        // Changing one card breaks the exact match, and the replacement is not theme-legal either.
        ((ArrayNode) d.path("piles").path("deck")).set(0, JSON.getNodeFactory().textNode(trainers.get(9).guid()));
        assertFalse(row(v.validate(d, ownedAll()), FormatManager.THEME).valid());
    }

    @Test
    void unknownCardsAndMissingBasicPokemon() {
        ObjectNode d = deck(trainers.get(0), 4);
        ((ArrayNode) d.path("piles").path("deck")).add("zzz");
        List<DeckValidationRow.Failure> f = validator.baseFailures(d, null);
        assertEquals(List.of("ExactSize", "MustNotContain", "MustContain"), types(f));
        assertEquals(List.of("zzz"), f.get(1).offendingArchetypeIds());
    }

    @Test
    void vUnionPiecesCountPerPieceAndPrismStarPerName() {
        Card v1 = card("v1", "Pikachu V-UNION", STANDARD_SET, 139, 1, 8, false, "V-UNION");
        Card v2 = card("v2", "Pikachu V-UNION", STANDARD_SET, 140, 1, 8, false, "V-UNION");
        Card prism = card("ps", "Prism Mon", STANDARD_SET, 50, 0, 0, false, "Basic", "Prism Star");
        rebuild();
        assertTrue(validator.baseFailures(deck(pikachu, 4, v1, 4, v2, 4, prism, 1, fireEnergy, 47), null).isEmpty());
        assertEquals(List.of("MaxDuplicates", "MaxMatches"),
                types(validator.baseFailures(deck(pikachu, 4, v1, 5, prism, 2, fireEnergy, 49), null)));
    }

    @Test
    void rowJsonShapeAndClientFilter() {
        List<DeckValidationRow> rows = validator.validate(legalDeck(), ownedAll());
        ArrayNode client = DeckValidationRow.toClientRows(rows);
        assertEquals(4, client.size());
        JsonNode theme = null;
        for (JsonNode r : client) {
            assertNotEquals("TrainerChallenge", r.get("formatName").asText());
            if (r.get("format").asText().equals(FormatManager.THEME)) theme = r;
        }
        assertNotNull(theme);
        assertEquals("deck-1", theme.get("deckID").asText());
        assertFalse(theme.get("valid").asBoolean());
        JsonNode detail = theme.get("results").get(0);
        assertEquals("DeckContainsBannedCards", detail.get("failureType").asText()); // no theme-legal cards
        assertEquals("BoolRestriction", theme.get("results").get(1).get("failureType").asText());
        assertTrue(detail.get("explanation").has("id"));
        assertTrue(detail.get("offendingArchetypeIDs").isArray());
        assertTrue(detail.get("additionalData").isNull());
    }

    @Test
    void unknownFormatGuidGetsBoolRestrictionRow() {
        String bogus = "00000000-0000-0000-0000-000000000000";
        DeckValidationRow r = validator.validate(legalDeck(), ownedAll(), List.of(bogus)).get(0);
        assertFalse(r.valid());
        assertEquals(bogus, r.format());
        assertEquals(List.of("BoolRestriction"), types(r.failures()));
    }

    @Test
    void themeDeckAttributesAreReplacedNotDuplicated() {
        ObjectNode d = JSON.createObjectNode().put("deckName", "Water Practice");
        d.putArray("attributes").addObject().put("name", RulesDeckValidator.ATTR_IS_THEME_DECK).put("value", false);
        RulesDeckValidator.setThemeDeckAttributes(d, true);
        RulesDeckValidator.setThemeDeckAttributes(d, true);
        ArrayNode attrs = (ArrayNode) d.get("attributes");
        assertEquals(2, attrs.size()); // flag + theme deck name, no duplicates
        JsonNode on = null;
        JsonNode name = null;
        for (JsonNode a : attrs) {
            if (a.get("name").asInt() == RulesDeckValidator.ATTR_IS_THEME_DECK) on = a;
            if (a.get("name").asInt() == RulesDeckValidator.ATTR_THEME_DECK_NAME) name = a;
        }
        assertTrue(on.get("value").asBoolean());
        assertEquals("Water Practice", name.get("value").get("id").asText());
        RulesDeckValidator.setThemeDeckAttributes(d, false);
        JsonNode flag = null;
        for (JsonNode a : d.get("attributes")) if (a.get("name").asInt() == RulesDeckValidator.ATTR_IS_THEME_DECK) flag = a;
        assertNotNull(flag);
        assertFalse(flag.get("value").asBoolean());
    }
}
