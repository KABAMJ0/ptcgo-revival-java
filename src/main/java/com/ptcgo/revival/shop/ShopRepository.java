package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Shop rows, product claims and the transactional purchase/open operations.
 * Mirrors ptcgo/database/economy_data.py (shop items, purchase_products) and deck_products.py (open_products).
 */
public final class ShopRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ShopRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String PURCHASE_ERROR_FUNDS = "You don't have enough currency for this purchase.";
    public static final String PURCHASE_ERROR_CURRENCY = "This item can't be bought with that currency.";
    public static final String PURCHASE_ERROR_ONCE =
            "You already have this item. It is limited to one per account, so you have not been charged.";

    private final Database db;

    public ShopRepository(Database db) {
        this.db = db;
    }

    // ---- shop items ------------------------------------------------------------------------------

    public record ShopItem(long id, String productGuid, String displayName, long currency, long price, boolean enabled,
                           boolean featured, boolean topSelling, int sortOrder) {}

    /** Null fields leave the stored value unchanged (new rows get the model defaults). */
    public record ItemUpdate(String displayName, Long currency, Long price, Boolean enabled, Boolean featured,
                             Boolean topSelling, Integer sortOrder, Long itemId) {
        public static ItemUpdate none() {
            return new ItemUpdate(null, null, null, null, null, null, null, null);
        }
    }

    public List<ShopItem> listShopItems(boolean enabledOnly) throws SQLException {
        List<ShopItem> out = new ArrayList<>();
        try (Connection c = db.connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, product_guid, display_name, currency, price, enabled, featured, "
                     + "top_selling, sort_order FROM shop_items" + (enabledOnly ? " WHERE enabled = 1" : "")
                     + " ORDER BY sort_order, id")) {
            while (rs.next()) out.add(map(rs));
        }
        return out;
    }

    private static ShopItem map(ResultSet rs) throws SQLException {
        return new ShopItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8), rs.getInt(9));
    }

    /** Creates or updates a shop item, matched by id first, then by product guid. */
    public ShopItem upsertShopItem(String productGuid, ItemUpdate u) throws SQLException {
        return Tx.run(db, c -> {
            ShopItem item = null;
            if (u.itemId() != null) item = byId(c, u.itemId());
            if (item == null) item = byGuid(c, productGuid);
            long id;
            if (item == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO shop_items (product_guid, display_name, currency, price, enabled, featured, top_selling, sort_order) "
                                + "VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, productGuid);
                    ps.setString(2, u.displayName() == null ? "" : u.displayName());
                    ps.setLong(3, u.currency() == null ? Currency.TRAINER_TOKENS : u.currency());
                    ps.setLong(4, u.price() == null ? 200 : u.price());
                    ps.setBoolean(5, u.enabled() == null || u.enabled());
                    ps.setBoolean(6, u.featured() != null && u.featured());
                    ps.setBoolean(7, u.topSelling() != null && u.topSelling());
                    ps.setInt(8, u.sortOrder() == null ? 0 : u.sortOrder());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                }
            } else {
                id = item.id();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE shop_items SET product_guid=?, display_name=?, currency=?, price=?, enabled=?, featured=?, "
                                + "top_selling=?, sort_order=? WHERE id=?")) {
                    ps.setString(1, productGuid);
                    ps.setString(2, u.displayName() != null ? u.displayName() : item.displayName());
                    ps.setLong(3, u.currency() != null ? u.currency() : item.currency());
                    ps.setLong(4, u.price() != null ? u.price() : item.price());
                    ps.setBoolean(5, u.enabled() != null ? u.enabled() : item.enabled());
                    ps.setBoolean(6, u.featured() != null ? u.featured() : item.featured());
                    ps.setBoolean(7, u.topSelling() != null ? u.topSelling() : item.topSelling());
                    ps.setInt(8, u.sortOrder() != null ? u.sortOrder() : item.sortOrder());
                    ps.setLong(9, id);
                    ps.executeUpdate();
                }
            }
            return byId(c, id);
        });
    }

    private static ShopItem byId(Connection c, long id) throws SQLException {
        return one(c, "id = ?", String.valueOf(id));
    }

    private static ShopItem byGuid(Connection c, String guid) throws SQLException {
        return one(c, "product_guid = ?", guid);
    }

    private static ShopItem one(Connection c, String where, String arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, product_guid, display_name, currency, price, enabled, "
                + "featured, top_selling, sort_order FROM shop_items WHERE " + where)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public boolean deleteShopItem(long id) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM shop_items WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** A product that ships for sale (shop_defaults.json, the Python DEFAULT_OFFERS). */
    public record DefaultOffer(String guid, String name, long price, int sortOrder, List<Long> previousPrices) {}

    public static List<DefaultOffer> loadDefaultOffers(Path catalogDir) throws IOException {
        Path file = catalogDir.resolve("shop_defaults.json");
        List<DefaultOffer> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        for (JsonNode n : JSON.readTree(file.toFile())) {
            List<Long> prev = new ArrayList<>();
            n.path("previous_prices").forEach(p -> prev.add(p.asLong()));
            out.add(new DefaultOffer(n.path("guid").asText(), n.path("name").asText(), n.path("price").asLong(),
                    n.path("sort_order").asInt(), prev));
        }
        return out;
    }

    /**
     * Adds the shipped shop rows that are missing; never overwrites an existing one, except that a row still at a
     * price we shipped earlier moves to the new price (an admin-chosen price is left alone). Idempotent.
     */
    public void ensureDefaultShopItems(List<DefaultOffer> offers) {
        Map<String, ShopItem> existing = new HashMap<>();
        try {
            for (ShopItem i : listShopItems(false)) existing.put(i.productGuid().toLowerCase(Locale.ROOT), i);
        } catch (SQLException e) {
            LOG.warn("[Shop] Could not read shop items to seed defaults: {}", e.getMessage());
            return;
        }
        for (DefaultOffer o : offers) {
            ShopItem row = existing.get(o.guid().toLowerCase(Locale.ROOT));
            try {
                if (row != null) {
                    if (o.previousPrices().contains(row.price())) {
                        upsertShopItem(o.guid(), new ItemUpdate(null, null, o.price(), null, null, null, null, row.id()));
                        LOG.info("[Shop] Repriced {} from {} to {} tokens", o.name(), row.price(), o.price());
                    }
                    continue;
                }
                upsertShopItem(o.guid(), new ItemUpdate(o.name(), Currency.TRAINER_TOKENS, o.price(), true, true, true,
                        o.sortOrder(), null));
                LOG.info("[Shop] Seeded shop item {} at {} tokens", o.name(), o.price());
            } catch (SQLException e) {
                LOG.warn("[Shop] Could not seed shop item {}: {}", o.name(), e.getMessage());
            }
        }
    }

    // ---- claims ----------------------------------------------------------------------------------

    public boolean hasClaim(String accountId, String productGuid) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM product_claims WHERE account_id = ? AND product_guid = ?")) {
            ps.setString(1, accountId);
            ps.setString(2, productGuid.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ---- purchase --------------------------------------------------------------------------------

    /**
     * Deducts {currency: amount}, grants {archetype: count} as tradable, credits {currency: amount} (a bundle's tokens)
     * and records one-per-account claims, all in ONE transaction. Returns an error message (nothing changed) or null.
     * A claim already on record, or the same claim twice in one request, refuses the whole purchase.
     */
    public String purchase(String accountId, Map<Long, Long> charges, Map<String, Integer> grants,
                           Map<Long, Long> credit, List<String> claimsIn) throws SQLException {
        List<String> claims = new ArrayList<>();
        for (String s : claimsIn) claims.add(s.toLowerCase(Locale.ROOT));
        if (Set.copyOf(claims).size() != claims.size()) return PURCHASE_ERROR_ONCE;
        try {
            return Tx.run(db, c -> {
                if (!claims.isEmpty()) {
                    for (String guid : claims) {
                        if (hasClaim(c, accountId, guid)) return PURCHASE_ERROR_ONCE;
                    }
                }
                String err = charge(c, accountId, charges);
                if (err != null) throw new Tx.Rollback(err);
                for (Map.Entry<Long, Long> e : credit.entrySet()) {
                    String field = Currency.walletField(e.getKey());
                    if (field == null || !walletExists(c, accountId)) throw new Tx.Rollback(PURCHASE_ERROR_CURRENCY);
                    addToWallet(c, accountId, field, e.getValue());
                }
                for (Map.Entry<String, Integer> e : grants.entrySet()) Tx.addCopies(c, accountId, e.getKey(), e.getValue(), 0);
                for (String guid : claims) insertClaim(c, accountId, guid);
                return null;
            });
        } catch (SQLException e) {
            if (Tx.isUniqueViolation(e)) return PURCHASE_ERROR_ONCE; // the unique constraint backs the lock up
            throw e;
        }
    }

    private static boolean hasClaim(Connection c, String accountId, String guid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM product_claims WHERE account_id = ? AND product_guid = ?")) {
            ps.setString(1, accountId);
            ps.setString(2, guid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static void insertClaim(Connection c, String accountId, String guid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO product_claims (account_id, product_guid) VALUES (?,?)")) {
            ps.setString(1, accountId);
            ps.setString(2, guid);
            ps.executeUpdate();
        }
    }

    private static boolean walletExists(Connection c, String accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM wallets WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void addToWallet(Connection c, String accountId, String field, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE wallets SET " + field + " = MIN(" + field + " + ?, "
                + PlayerRepository.WALLET_MAX + ") WHERE account_id = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, accountId);
            ps.executeUpdate();
        }
    }

    /** Debits every positive charge; returns an error message and changes nothing when one cannot be paid. */
    private static String charge(Connection c, String accountId, Map<Long, Long> charges) throws SQLException {
        Map<String, Long> due = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : charges.entrySet()) {
            if (e.getValue() <= 0) continue;
            String field = Currency.walletField(e.getKey());
            if (field == null) return PURCHASE_ERROR_CURRENCY;
            due.merge(field, e.getValue(), Long::sum);
        }
        if (due.isEmpty()) return null;
        long[] balance = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT coins, gems, tickets FROM wallets WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) balance = new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)};
            }
        }
        if (balance == null) return PURCHASE_ERROR_FUNDS;
        Map<String, Integer> idx = Map.of("coins", 0, "gems", 1, "tickets", 2);
        for (Map.Entry<String, Long> e : due.entrySet()) if (balance[idx.get(e.getKey())] < e.getValue()) return PURCHASE_ERROR_FUNDS;
        for (Map.Entry<String, Long> e : due.entrySet()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE wallets SET " + e.getKey() + " = " + e.getKey()
                    + " - ? WHERE account_id = ?")) {
                ps.setLong(1, e.getValue());
                ps.setString(2, accountId);
                ps.executeUpdate();
            }
        }
        return null;
    }

    // ---- opening ---------------------------------------------------------------------------------

    /** One opened product: its guid, what it yielded, and whether the yield is tradable. */
    public record OpenedRow(String productGuid, List<String> contents, boolean tradable) {}

    /** {@code error} (a message; nothing changed) or the opened rows. */
    public record OpenOutcome(String error, List<OpenedRow> opened) {}

    /** The client's saved-deck payload for a deck product (Deck.to_deck_dict). */
    public static ObjectNode deckData(ShopProduct product, String deckId) {
        ObjectNode d = JSON.createObjectNode();
        d.put("deckID", deckId);
        d.put("deckName", product.name());
        ArrayNode cards = d.putObject("piles").putArray("deck");
        product.contents().forEach(cards::add);
        ArrayNode attrs = d.putArray("attributes");
        attrs.addObject().put("name", Cosmetics.IS_THEME_DECK).put("value", product.themeDeck() && product.themeLegal());
        attrs.addObject().put("name", Cosmetics.SELECTED_COIN).put("value", Cosmetics.DEFAULT_COIN);
        attrs.addObject().put("name", Cosmetics.SELECTED_SLEEVE).put("value", Cosmetics.DEFAULT_SLEEVE);
        attrs.addObject().put("name", Cosmetics.SELECTED_DECK_BOX).put("value",
                product.deckBox() != null ? product.deckBox() : Cosmetics.DEFAULT_DECK_BOX);
        return d;
    }

    /** The saved-deck id a deck product opened by an account gets (idempotent per account and product). */
    public static String deckProductId(String accountId, String productGuid) {
        return Uuid5.of(Uuid5.NAMESPACE_URL, "spirit:deck-product:" + accountId + ":" + productGuid).toString();
    }

    /**
     * Opens a request atomically. With {@code purchased} false each product is first consumed from the collection
     * (non-tradable copies first); with true it is granted straight away. {@code charges} (the legacy purchase-and-open
     * path) are debited in the same transaction, so a failed opening never costs the player. Throws
     * {@link IllegalArgumentException} when the account does not own enough of a requested product.
     */
    public OpenOutcome openProducts(String accountId, List<ShopProduct> items, boolean purchased, Boolean tradableOverride,
                                    Map<Long, Long> charges, Function<ShopProduct, List<String>> opener) throws SQLException {
        if (items.isEmpty()) return new OpenOutcome(null, List.of());
        return Tx.run(db, c -> {
            String err = charge(c, accountId, charges);
            if (err != null) throw new Tx.Rollback(new OpenOutcome(err, List.of()));
            Map<String, int[]> owned = new HashMap<>(); // product guid (exact case) -> {tradable, nontradable}
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tradable_count, nontradable_count FROM collection WHERE account_id = ? AND archetype_id = ?")) {
                for (ShopProduct p : items) {
                    if (owned.containsKey(p.guid())) continue;
                    ps.setString(1, accountId);
                    ps.setString(2, p.guid());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) owned.put(p.guid(), new int[] {rs.getInt(1), rs.getInt(2)});
                    }
                }
            }
            Map<String, int[]> grants = new LinkedHashMap<>(); // card guid -> {tradable, nontradable}
            Map<String, int[]> consumed = new LinkedHashMap<>();
            List<OpenedRow> opened = new ArrayList<>();
            Map<String, ShopProduct> decks = new LinkedHashMap<>();
            for (ShopProduct p : items) {
                boolean tradable = true;
                int[] item = owned.get(p.guid());
                if (!purchased) tradable = item == null || item[1] <= 0;
                if (tradableOverride != null && !p.isDeck()) tradable = tradableOverride;
                int field = tradable ? 0 : 1;
                if (!purchased) {
                    if (item == null || item[field] <= 0) {
                        throw new IllegalArgumentException("The account does not own enough of the requested products");
                    }
                    item[field]--;
                    consumed.computeIfAbsent(p.guid(), k -> new int[2])[field]++;
                }
                List<String> contents = List.copyOf(opener.apply(p));
                for (String g : contents) grants.computeIfAbsent(g, k -> new int[2])[field]++;
                opened.add(new OpenedRow(p.guid(), contents, tradable));
                if (p.isDeck()) decks.put(deckProductId(accountId, p.guid()), p);
            }
            for (Map.Entry<String, int[]> e : consumed.entrySet()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE collection SET tradable_count = tradable_count - ?, "
                        + "nontradable_count = nontradable_count - ? WHERE account_id = ? AND archetype_id = ?")) {
                    ps.setInt(1, e.getValue()[0]);
                    ps.setInt(2, e.getValue()[1]);
                    ps.setString(3, accountId);
                    ps.setString(4, e.getKey());
                    ps.executeUpdate();
                }
            }
            for (Map.Entry<String, int[]> e : grants.entrySet()) Tx.addCopies(c, accountId, e.getKey(), e.getValue()[0], e.getValue()[1]);
            long tokens = 0;
            for (ShopProduct p : items) tokens += p.tokens();
            if (tokens > 0) {
                if (!walletExists(c, accountId)) throw new IllegalStateException("The account has no wallet to credit");
                addToWallet(c, accountId, "coins", tokens);
            }
            for (ShopProduct p : items) {
                if (p.oncePerAccount() && !hasClaim(c, accountId, p.lowerGuid())) insertClaim(c, accountId, p.lowerGuid());
            }
            for (Map.Entry<String, ShopProduct> e : decks.entrySet()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO decks (id, account_id, name, deck_data, is_avatar) "
                        + "VALUES (?,?,?,?,0)")) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, accountId);
                    ps.setString(3, e.getValue().name());
                    ps.setString(4, deckData(e.getValue(), e.getKey()).toString());
                    ps.executeUpdate();
                }
            }
            return new OpenOutcome(null, opened);
        });
    }
}
