package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Starter content for a freshly created account (ptcgo/game/content/starter.py grant_starter_content): wallet
 * defaults, the four starter decks and their cards, the default coin/sleeve/deck box, and tradable packs of each
 * listed Sword and Shield set. Everything is written in ONE transaction, and a {@code product_claims} marker row
 * (unique per account) makes the grant happen at most once, however often it is called.
 */
public final class StarterContent {
    private static final Logger LOG = LoggerFactory.getLogger(StarterContent.class);
    /** Claim row recording that an account has had its starter content. */
    public static final String CLAIM = "spirit:starter-content";
    private static final List<String> COSMETICS = List.of(Cosmetics.DEFAULT_COIN, Cosmetics.DEFAULT_SLEEVE, Cosmetics.DEFAULT_DECK_BOX);

    private final Database db;
    private final Catalog catalog;
    private final ShopProducts products;
    private final PackMerges merges;
    private final JsonNode config;
    private Map<String, String> cardIndex; // "SET#number" -> guid

    public StarterContent(Database db, Catalog catalog, ShopProducts products, PackMerges merges, JsonNode config) {
        this.db = db;
        this.catalog = catalog;
        this.products = products;
        this.merges = merges;
        this.config = config;
    }

    public StarterContent(Database db, Catalog catalog, ShopProducts products, PackMerges merges) {
        this(db, catalog, products, merges, Resources.read("starter.json"));
    }

    private synchronized Map<String, String> cardIndex() {
        if (cardIndex == null) {
            Map<String, String> m = new HashMap<>();
            for (Catalog.Card c : catalog.cards()) m.put(c.key().toUpperCase(Locale.ROOT) + "#" + c.collectorNumber(), c.guid());
            cardIndex = m;
        }
        return cardIndex;
    }

    /** Expands [count, live set code, number] entries into a flat guid list, skipping cards the catalogue lacks. */
    List<String> resolveDecklist(JsonNode cards) {
        List<String> guids = new ArrayList<>();
        JsonNode codeMap = config.path("set_code_map");
        for (JsonNode e : cards) {
            String live = e.get(1).asText();
            String set = codeMap.path(live).asText(live);
            String guid = cardIndex().get(set.toUpperCase(Locale.ROOT) + "#" + e.get(2).asInt());
            if (guid == null) {
                LOG.warn("[Starter] Card not found: {} {} (set {})", live, e.get(2).asInt(), set);
                continue;
            }
            for (int i = 0; i < e.get(0).asInt(); i++) guids.add(guid);
        }
        return guids;
    }

    /** Pack products for every eligible set that is sold as its own pack. */
    public List<ShopProduct> starterBoosterPacks() {
        Set<String> eligible = ShopManager.eligibleSets(catalog);
        List<ShopProduct> out = new ArrayList<>();
        for (ShopProduct p : products.all()) {
            if (p.productType() == ShopProduct.TYPE_PACKS && !p.isBundle() && eligible.contains(p.key().toUpperCase(Locale.ROOT))
                    && !merges.isHiddenPack(p.key())) out.add(p);
        }
        return out;
    }

    /** Packs granted as tradable: one per listed set. */
    public List<ShopProduct> starterTradablePacks() {
        Set<String> wanted = new java.util.HashSet<>();
        config.path("tradable_pack_sets").forEach(s -> wanted.add(s.asText().toUpperCase(Locale.ROOT)));
        List<ShopProduct> out = new ArrayList<>();
        for (ShopProduct p : starterBoosterPacks()) if (wanted.contains(p.key().toUpperCase(Locale.ROOT))) out.add(p);
        return out;
    }

    private static ObjectNode deckData(String name, List<String> cards) {
        ObjectNode d = Resources.JSON.createObjectNode();
        String id = UUID.randomUUID().toString();
        d.put("deckID", id);
        d.put("deckName", name);
        ArrayNode pile = d.putObject("piles").putArray("deck");
        cards.forEach(pile::add);
        ArrayNode attrs = d.putArray("attributes");
        attrs.addObject().put("name", Cosmetics.SELECTED_COIN).put("value", Cosmetics.DEFAULT_COIN);
        attrs.addObject().put("name", Cosmetics.SELECTED_SLEEVE).put("value", Cosmetics.DEFAULT_SLEEVE);
        attrs.addObject().put("name", Cosmetics.SELECTED_DECK_BOX).put("value", Cosmetics.DEFAULT_DECK_BOX);
        return d;
    }

    /**
     * Grants the starter content. Returns true if it was granted now, false if this account already had it.
     * Call it once right after the account row is created; calling it again is harmless.
     */
    public boolean grant(String accountId) throws SQLException {
        // Resolve outside the transaction: pure computation over the catalogue.
        List<ObjectNode> decks = new ArrayList<>();
        Map<String, Integer> nontradable = new LinkedHashMap<>();
        for (JsonNode deck : config.path("decks")) {
            List<String> cards = resolveDecklist(deck.path("cards"));
            if (cards.size() != 60) LOG.warn("[Starter] Deck '{}' resolved {}/60 cards.", deck.path("name").asText(), cards.size());
            decks.add(deckData(deck.path("name").asText(), cards));
            for (String g : cards) nontradable.merge(g, 1, Integer::sum);
        }
        int packCount = config.path("starter_booster_pack_count").asInt(0);
        if (packCount > 0) for (ShopProduct p : starterBoosterPacks()) nontradable.merge(p.guid(), packCount, Integer::sum);
        for (String cosmetic : COSMETICS) nontradable.merge(cosmetic, 1, Integer::sum);
        Map<String, Integer> tradable = new LinkedHashMap<>();
        int perSet = config.path("tradable_packs_per_set").asInt(0);
        if (perSet > 0) for (ShopProduct p : starterTradablePacks()) tradable.merge(p.guid(), perSet, Integer::sum);

        boolean granted = Tx.run(db, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM product_claims WHERE account_id = ? AND product_guid = ?")) {
                ps.setString(1, accountId);
                ps.setString(2, CLAIM);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) throw new Tx.Rollback(Boolean.FALSE);
                }
            }
            ShopRepository.insertClaim(c, accountId, CLAIM);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO wallets (account_id, coins, gems, tickets) VALUES (?,?,?,?) "
                    + "ON CONFLICT(account_id) DO UPDATE SET coins=excluded.coins, gems=excluded.gems, tickets=excluded.tickets")) {
                ps.setString(1, accountId);
                ps.setInt(2, PlayerRepository.STARTING_COINS);
                ps.setInt(3, PlayerRepository.STARTING_GEMS);
                ps.setInt(4, PlayerRepository.STARTING_TICKETS);
                ps.executeUpdate();
            }
            for (ObjectNode deck : decks) saveDeck(c, accountId, deck);
            for (Map.Entry<String, Integer> e : nontradable.entrySet()) Tx.addCopies(c, accountId, e.getKey(), 0, e.getValue());
            for (Map.Entry<String, Integer> e : tradable.entrySet()) Tx.addCopies(c, accountId, e.getKey(), e.getValue(), 0);
            return Boolean.TRUE;
        });
        if (granted) LOG.info("[Starter] Granted starter decks, packs and cosmetics to account {}", accountId);
        return granted;
    }

    private static void saveDeck(Connection c, String accountId, ObjectNode deck) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO decks (id, account_id, name, deck_data, is_avatar) VALUES (?,?,?,?,0) "
                + "ON CONFLICT(id) DO UPDATE SET account_id=excluded.account_id, name=excluded.name, deck_data=excluded.deck_data, is_avatar=0")) {
            ps.setString(1, deck.get("deckID").asText());
            ps.setString(2, accountId);
            ps.setString(3, deck.get("deckName").asText());
            ps.setString(4, deck.toString());
            ps.executeUpdate();
        }
    }
}
