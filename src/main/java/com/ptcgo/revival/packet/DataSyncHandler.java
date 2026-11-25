package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.deck.DeckValidationRow;
import com.ptcgo.revival.deck.FormatManager;
import com.ptcgo.revival.deck.RulesDeckValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Wallet, collection, archetype flags, decks and account settings: the data the client syncs after login.
 *
 * <p>Not ported yet (Python: ptcgo/packets/handlers/data_sync.py): archetype/set catalogue downloads,
 * protobuf lists, dynamic pages, notifications, MOTD, localization releases, feature statuses, and real
 * deck validation (see {@link com.ptcgo.revival.deck.DeckValidator}).
 */
public final class DataSyncHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(DataSyncHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final long TRAINER_TOKENS = -1784319558L;
    static final long REAL_CURRENCY = 2012906479L;
    static final long EVENT_TICKETS = -992199324L;
    static final int VALID_FORMATS = 10860;
    static final int SELECTED_COIN = 200670;
    static final int SELECTED_SLEEVE = 200680;
    static final int SELECTED_DECK_BOX = 200690;
    static final String DEFAULT_COIN = "B9A4EA96-949E-11E1-890F-EFB676C7909C";
    static final String DEFAULT_SLEEVE = "e079c0d3-b934-4fbd-b021-545106c75693";
    static final String DEFAULT_DECK_BOX = "e129b0d3-b934-4fbd-b021-545106c75694";
    static final String NIL_GUID = "00000000-0000-0000-0000-000000000000";
    private static final Map<Integer, String> COSMETIC_DEFAULTS = Map.of(
            SELECTED_COIN, DEFAULT_COIN, SELECTED_SLEEVE, DEFAULT_SLEEVE, SELECTED_DECK_BOX, DEFAULT_DECK_BOX);

    @Override
    public Map<String, MessageHandler> handlers() {
        Map<String, MessageHandler> m = new HashMap<>();
        m.put("GetWallet", DataSyncHandler::getWallet);
        m.put("GetCollectionCount", DataSyncHandler::getCollectionCount);
        m.put("GetArchetypeFlags", DataSyncHandler::getArchetypeFlags);
        m.put("GetDeckList", DataSyncHandler::getDeckList);
        m.put("GetAvatarDeckList", DataSyncHandler::getAvatarDeckList);
        m.put("CakeSaveDeck", DataSyncHandler::saveDeck);
        m.put("CakeDeleteDeck", DataSyncHandler::deleteDeck);
        m.put("ValidateDecks", DataSyncHandler::validateDecks);
        m.put("ValidateAllDecks", DataSyncHandler::validateAllDecks);
        m.put("SetAccountSettings", DataSyncHandler::setAccountSettings);
        m.put("GetArchetypeCorrections", (c, v, rid, f) -> c.send("ArchetypeCorrections", Map.of("corrections", List.of()), rid));
        m.put("AcknowledgeNotification", (c, v, rid, f) -> LOG.info("[TCP] [{}] Acknowledged notification {}",
                c.remoteAddress(), v == null ? null : v.path("notificationID").asText()));
        m.put("UserHasVisitedScene", (c, v, rid, f) -> {
            String scene = v == null ? null : v.path("sceneName").asText();
            c.state().lastScene = scene;
            LOG.info("[TCP] [{}] Visited scene {}", c.remoteAddress(), scene);
        });
        return m;
    }

    // ---- wallet / collection / flags -------------------------------------------------------------

    static Map<String, Object> walletPayload(PlayerRepository.Wallet w) {
        return Map.of("currencies", List.of(
                Map.of("name", TRAINER_TOKENS, "value", w.coins()),
                Map.of("name", REAL_CURRENCY, "value", w.gems()),
                Map.of("name", EVENT_TICKETS, "value", w.tickets())));
    }

    private static void getWallet(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        PlayerRepository.Wallet w = c.account() == null
                ? new PlayerRepository.Wallet(0, 0, 0)
                : c.services().players().wallet(c.account().accountId());
        c.send("CurrentWallet", walletPayload(w), rid);
    }

    private static void getCollectionCount(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        c.send("CollectionCountFound", Map.of("collectionCountList", c.account() == null ? List.of() : collectionPayload(c)), rid);
    }

    /** Owned rows plus unlimited basic energy (99 tradable) for everyone, as on the live game. */
    static List<Map<String, Object>> collectionPayload(ClientContext c) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PlayerRepository.CollectionRow r : c.services().players().collection(c.account().accountId())) {
            String guid = r.archetypeId().toLowerCase(Locale.ROOT);
            if (!seen.add(guid)) continue;
            out.add(Map.of("archetypeID", guid, "tradable", r.tradable(), "nontradable", r.nontradable()));
        }
        for (String guid : c.services().catalog().freeEnergyGuids()) {
            if (seen.add(guid)) out.add(Map.of("archetypeID", guid, "tradable", 99, "nontradable", 0));
        }
        return out;
    }

    private static void getArchetypeFlags(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        List<Map<String, Object>> flags = new ArrayList<>();
        if (c.account() != null) {
            for (PlayerRepository.Flag fl : c.services().players().flags(c.account().accountId())) {
                flags.add(Map.of("archetypeID", fl.archetypeId(), "wanted", fl.wanted(), "forTrade", fl.forTrade(),
                        "review", fl.review()));
            }
        }
        c.send("ArchetypeFlagsSet", Map.of("archetypeFlags", flags), rid);
    }

    // ---- decks -----------------------------------------------------------------------------------

    private static void getDeckList(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        List<JsonNode> decks = new ArrayList<>();
        if (c.account() != null) {
            Set<String> allowed = allowedCosmetics(c);
            Map<String, Integer> owned = c.services().players().ownedCounts(c.account().accountId());
            for (PlayerRepository.DeckRow d : c.services().players().decks(c.account().accountId(), false)) {
                ObjectNode copy = d.deckData().deepCopy() instanceof ObjectNode o ? o : JSON.createObjectNode();
                injectValidFormats(c, copy, owned);
                fixCosmetics(copy, allowed);
                decks.add(copy);
            }
        }
        c.send("OnlineDecksFound", Map.of("decks", decks), rid);
        // The client only builds its per-deck validation models once it receives DecksValidated.
        c.send("DecksValidated", Map.of("results", List.of()), rid);
    }

    private static void getAvatarDeckList(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        List<JsonNode> decks = new ArrayList<>();
        if (c.account() != null) {
            for (PlayerRepository.DeckRow d : c.services().players().decks(c.account().accountId(), true)) {
                ObjectNode copy = d.deckData().deepCopy() instanceof ObjectNode o ? o : JSON.createObjectNode();
                setValidFormats(copy, List.of("Modified", "Expanded", "Unlimited", "Legacy"));
                decks.add(copy);
            }
        }
        // Python also fabricates a default avatar deck when none exists; that needs the avatar item catalogue.
        c.send("OnlineAvatarDecksFound", Map.of("decks", decks), rid);
    }

    private static void saveDeck(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null || v == null || !v.path("deck").isObject()) {
            LOG.warn("[TCP] [{}] SaveDeck without login or 'deck' object", c.remoteAddress());
            return;
        }
        String accountId = c.account().accountId();
        ObjectNode deck = v.get("deck").deepCopy();
        String deckId = deck.path("deckID").asText("");
        if (deckId.isBlank() || deckId.equals(NIL_GUID)) {
            deckId = UUID.randomUUID().toString();
            deck.put("deckID", deckId);
        }
        String name = deck.path("deckName").asText("");
        boolean avatar = isAvatarDeck(deck);
        List<DeckValidationRow> rows = List.of();

        // Attribute list -> map for lookup, default cosmetics, back to a list.
        Map<String, ObjectNode> attrs = new LinkedHashMap<>();
        for (JsonNode a : deck.path("attributes")) {
            if (a.isObject() && a.has("name")) attrs.put(a.get("name").asText(), (ObjectNode) a);
        }
        COSMETIC_DEFAULTS.forEach((id, def) -> attrs.computeIfAbsent(String.valueOf(id), k -> attr(id, def)));
        ArrayNode list = JSON.createArrayNode();
        attrs.values().forEach(list::add);
        deck.set("attributes", list);

        if (!avatar) {
            fixCosmetics(deck, allowedCosmetics(c));
            Map<String, Integer> owned = c.services().players().ownedCounts(accountId);
            var validator = c.services().deckValidator();
            rows = validator.validate(deck, owned);
            List<String> names = rows.isEmpty() ? validator.validFormats(deck, owned)
                    : rows.stream().filter(DeckValidationRow::valid).map(DeckValidationRow::formatName).toList();
            setValidFormats(deck, names);
            RulesDeckValidator.setThemeDeckAttributes(deck, names.contains("ThemeDeck"));
        }
        // An illegal deck still saves: PTCGO keeps work-in-progress decks.
        c.services().players().saveDeck(accountId, deckId, name, deck, avatar);
        LOG.info("[TCP] [{}] Saved deck '{}' ({}) avatar={}", c.remoteAddress(), name, deckId, avatar);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("deckID", deckId);
        res.put("deck", deck);
        res.put("validationResults", DeckValidationRow.toClientRows(rows));
        c.send("DeckSaved", res, rid);
    }

    private static void deleteDeck(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String deckId = firstText(v, "deckID", "deckId", "DeckID");
        if (deckId == null) {
            LOG.warn("[TCP] [{}] DeleteDeck without a deck id", c.remoteAddress());
            return;
        }
        if (c.account() != null) c.services().players().deleteDeck(c.account().accountId(), deckId);
        c.send("DeckDeleted", Map.of("deckID", deckId), rid);
    }

    /** ValidateDecks carries full deck contents (the builder validates unsaved decks); ValidateAllDecks the saved ones. */
    private static void validateDecks(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        validateAndReply(c, v, rid, v != null && v.path("decks").isArray() ? decksOf(v.get("decks")) : null);
    }

    private static void validateAllDecks(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        List<JsonNode> saved = new ArrayList<>();
        if (c.account() != null) {
            for (PlayerRepository.DeckRow d : c.services().players().decks(c.account().accountId(), false)) saved.add(d.deckData());
        }
        validateAndReply(c, v, rid, saved);
    }

    private static List<JsonNode> decksOf(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private static void validateAndReply(ClientContext c, JsonNode request, int rid, List<JsonNode> decks)
            throws SQLException {
        List<String> formats = new ArrayList<>();
        if (request != null && request.path("formats").isArray()) request.get("formats").forEach(n -> formats.add(n.asText()));
        if (formats.isEmpty()) formats.addAll(FormatManager.DEFAULT_VALIDATION_FORMATS);
        Map<String, Integer> owned = c.account() == null ? null : c.services().players().ownedCounts(c.account().accountId());
        List<DeckValidationRow> rows = new ArrayList<>();
        if (decks != null) {
            for (JsonNode d : decks) {
                if (d.isObject() && !isAvatarDeck(d)) rows.addAll(c.services().deckValidator().validate(d, owned, formats));
            }
        }
        c.send("DecksValidated", Map.of("results", DeckValidationRow.toClientRows(rows)), rid);
    }

    private static void setAccountSettings(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (c.account() == null) return;
        Map<String, Object> incoming = v != null && v.path("settings").isObject()
                ? JSON.convertValue(v.get("settings"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                : Map.of();
        Map<String, Object> merged = c.services().players().mergeAccountSettings(c.account().accountId(), incoming);
        // SaveAccountSettings blocks until the settings attribute version bumps: AccountUpdated releases it.
        Map<String, Object> acct = new LinkedHashMap<>();
        acct.put("username", c.account().username());
        acct.put("accountID", c.account().accountId());
        acct.put("attributes", AuthHandler.accountAttributes(c.account().displayName(), merged));
        c.send("AccountUpdated", Map.of("account", acct), 0);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    static boolean isAvatarDeck(JsonNode deck) {
        return deck.path("piles").has("AvatarItems");
    }

    private static String firstText(JsonNode v, String... keys) {
        if (v == null) return null;
        for (String k : keys) if (v.hasNonNull(k) && !v.get(k).asText().isBlank()) return v.get(k).asText();
        return null;
    }

    private static ObjectNode attr(int name, Object value) {
        ObjectNode n = JSON.createObjectNode();
        n.put("name", name);
        n.set("value", JSON.valueToTree(value));
        return n;
    }

    /** Recomputes attribute 10860 (VALID_FORMATS): formats.json may have changed since the deck was saved. */
    private static void injectValidFormats(ClientContext c, ObjectNode deck, Map<String, Integer> owned) {
        // TrainerChallenge is ours: the client's format tabs know five names and a sixth leaves the deck in no category.
        List<String> names = c.services().deckValidator().validFormats(deck, owned).stream()
                .filter(n -> !n.equals("TrainerChallenge")).toList();
        setValidFormats(deck, names);
        RulesDeckValidator.setThemeDeckAttributes(deck, names.contains("ThemeDeck"));
    }

    static void setValidFormats(ObjectNode deck, List<String> names) {
        ArrayNode list = JSON.createArrayNode();
        for (JsonNode a : deck.path("attributes")) {
            if (a.path("name").asInt(-1) != VALID_FORMATS) list.add(a);
        }
        list.add(attr(VALID_FORMATS, names));
        deck.set("attributes", list);
    }

    /** Owned cosmetics, the defaults, and (later) the deck boxes shipped with deck products. */
    private static Set<String> allowedCosmetics(ClientContext c) throws SQLException {
        Set<String> allowed = new HashSet<>();
        for (PlayerRepository.CollectionRow r : c.services().players().collection(c.account().accountId())) {
            if (r.tradable() + r.nontradable() > 0) allowed.add(r.archetypeId().toLowerCase(Locale.ROOT));
        }
        COSMETIC_DEFAULTS.values().forEach(g -> allowed.add(g.toLowerCase(Locale.ROOT)));
        return allowed;
    }

    /** A coin, sleeve or deck box the account doesn't own leaves the whole deck unusable: reset it to the default. */
    static void fixCosmetics(ObjectNode deck, Set<String> allowed) {
        for (JsonNode a : deck.path("attributes")) {
            if (!(a instanceof ObjectNode entry)) continue;
            String def = COSMETIC_DEFAULTS.get(entry.path("name").asInt(Integer.MIN_VALUE));
            if (def == null) continue;
            JsonNode value = entry.get("value");
            if (value != null && value.isTextual() && allowed.contains(value.asText().toLowerCase(Locale.ROOT))) continue;
            entry.put("value", def);
        }
    }
}
