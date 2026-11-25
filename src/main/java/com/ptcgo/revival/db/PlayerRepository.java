package com.ptcgo.revival.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Wallet, decks, collection, archetype flags and account settings. Mirrors ptcgo/database/player_data.py. */
public final class PlayerRepository {
    public static final int STARTING_COINS = 1000;
    public static final int STARTING_GEMS = 0;
    public static final int STARTING_TICKETS = 100;
    /** The client parses wallet amounts as int32; larger values break the login scene. */
    public static final int WALLET_MAX = 2_000_000_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Database db;

    public PlayerRepository(Database db) {
        this.db = db;
    }

    public record Wallet(int coins, int gems, int tickets) {}

    public record DeckRow(String id, String accountId, String name, JsonNode deckData, boolean avatar,
                          int overallWins, int overallPlayed, int winsSinceLastEdit, int playedSinceLastEdit) {}

    public record CollectionRow(String archetypeId, int tradable, int nontradable) {}

    public record Flag(String archetypeId, int wanted, int forTrade, int review) {}

    // ---- wallet ----------------------------------------------------------------------------------

    /** Returns the wallet, creating a default one on first use. */
    public Wallet wallet(String accountId) throws SQLException {
        try (Connection c = db.connect()) {
            Optional<Wallet> w = readWallet(c, accountId);
            if (w.isPresent()) return w.get();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO wallets (account_id, coins, gems, tickets) VALUES (?,?,?,?)")) {
                ps.setString(1, accountId);
                ps.setInt(2, STARTING_COINS);
                ps.setInt(3, STARTING_GEMS);
                ps.setInt(4, STARTING_TICKETS);
                ps.executeUpdate();
            }
            return readWallet(c, accountId).orElse(new Wallet(STARTING_COINS, STARTING_GEMS, STARTING_TICKETS));
        }
    }

    private static Optional<Wallet> readWallet(Connection c, String accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT coins, gems, tickets FROM wallets WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Wallet(rs.getInt(1), rs.getInt(2), rs.getInt(3))) : Optional.empty();
            }
        }
    }

    public static int clampWallet(long v) {
        return (int) Math.max(0, Math.min(v, WALLET_MAX));
    }

    public void updateWallet(String accountId, long coins, long gems, long tickets) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wallets (account_id, coins, gems, tickets) VALUES (?,?,?,?) "
                        + "ON CONFLICT(account_id) DO UPDATE SET coins=excluded.coins, gems=excluded.gems, tickets=excluded.tickets")) {
            ps.setString(1, accountId);
            ps.setInt(2, clampWallet(coins));
            ps.setInt(3, clampWallet(gems));
            ps.setInt(4, clampWallet(tickets));
            ps.executeUpdate();
        }
    }

    // ---- decks -----------------------------------------------------------------------------------

    public List<DeckRow> decks(String accountId, boolean avatar) throws SQLException {
        List<DeckRow> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, account_id, name, deck_data, is_avatar, overall_wins, overall_played, "
                        + "wins_since_last_edit, played_since_last_edit FROM decks WHERE account_id = ? AND is_avatar = ?")) {
            ps.setString(1, accountId);
            ps.setBoolean(2, avatar);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonNode data;
                    try {
                        data = JSON.readTree(rs.getString(4));
                    } catch (Exception e) {
                        continue; // unreadable deck row: skip rather than break the whole list
                    }
                    out.add(new DeckRow(rs.getString(1), rs.getString(2), rs.getString(3), data, rs.getBoolean(5),
                            rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9)));
                }
            }
        }
        return out;
    }

    public void saveDeck(String accountId, String deckId, String name, JsonNode deckData, boolean avatar)
            throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO decks (id, account_id, name, deck_data, is_avatar) VALUES (?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET account_id=excluded.account_id, name=excluded.name, "
                        + "deck_data=excluded.deck_data, is_avatar=excluded.is_avatar")) {
            ps.setString(1, deckId);
            ps.setString(2, accountId);
            ps.setString(3, name);
            ps.setString(4, deckData.toString());
            ps.setBoolean(5, avatar);
            ps.executeUpdate();
        }
    }

    /** True if a deck of this account was deleted. */
    public boolean deleteDeck(String accountId, String deckId) throws SQLException {
        try (Connection c = db.connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM decks WHERE id = ? AND account_id = ?")) {
            ps.setString(1, deckId);
            ps.setString(2, accountId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean isAvatarDeck(String accountId, String deckId) throws SQLException {
        try (Connection c = db.connect();
             PreparedStatement ps = c.prepareStatement("SELECT is_avatar FROM decks WHERE id = ? AND account_id = ?")) {
            ps.setString(1, deckId);
            ps.setString(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    // ---- collection ------------------------------------------------------------------------------

    public List<CollectionRow> collection(String accountId) throws SQLException {
        List<CollectionRow> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT archetype_id, tradable_count, nontradable_count FROM collection WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new CollectionRow(rs.getString(1), rs.getInt(2), rs.getInt(3)));
            }
        }
        return out;
    }

    /** {archetype guid (lower-case): total owned copies}, for deck validation. */
    public Map<String, Integer> ownedCounts(String accountId) throws SQLException {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (CollectionRow r : collection(accountId)) m.merge(r.archetypeId().toLowerCase(Locale.ROOT), r.tradable() + r.nontradable(), Integer::sum);
        return m;
    }

    /** Adds copies in one transaction (upsert per archetype). */
    public void addToCollection(String accountId, Map<String, Integer> grants, boolean tradable) throws SQLException {
        if (grants.isEmpty()) return;
        String col = tradable ? "tradable_count" : "nontradable_count";
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO collection (account_id, archetype_id, tradable_count, nontradable_count) VALUES (?,?,?,?) "
                            + "ON CONFLICT(account_id, archetype_id) DO UPDATE SET " + col + " = " + col + " + excluded." + col)) {
                for (Map.Entry<String, Integer> e : grants.entrySet()) {
                    ps.setString(1, accountId);
                    ps.setString(2, e.getKey());
                    ps.setInt(3, tradable ? e.getValue() : 0);
                    ps.setInt(4, tradable ? 0 : e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    /** Removes copies (floored at 0). A missing row is created with 0 so a virtual starter copy can't reappear. */
    public void removeFromCollection(String accountId, String archetypeId, int count, boolean tradable) throws SQLException {
        String col = tradable ? "tradable_count" : "nontradable_count";
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT OR IGNORE INTO collection (account_id, archetype_id, tradable_count, nontradable_count) VALUES (?,?,0,0)")) {
                    ins.setString(1, accountId);
                    ins.setString(2, archetypeId);
                    ins.executeUpdate();
                }
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE collection SET " + col + " = MAX(0, " + col + " - ?) WHERE account_id = ? AND archetype_id = ?")) {
                    up.setInt(1, count);
                    up.setString(2, accountId);
                    up.setString(3, archetypeId);
                    up.executeUpdate();
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    /** The account's persisted client settings {settingNumber: value} (attribute 10230). */
    public Map<String, Object> accountSettings(String accountId) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT settings_json FROM accounts WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString(1) == null || rs.getString(1).isBlank()) return Map.of();
                try {
                    return JSON.readValue(rs.getString(1), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    return Map.of();
                }
            }
        }
    }

    // ---- archetype flags -------------------------------------------------------------------------

    public List<Flag> flags(String accountId) throws SQLException {
        List<Flag> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT archetype_id, wanted, for_trade, review FROM archetype_flags WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Flag(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
            }
        }
        return out;
    }

    /** Null arguments leave that flag unchanged; a row with all flags 0 is deleted. */
    public void setFlag(String accountId, String archetypeId, Integer wanted, Integer forTrade, Integer review)
            throws SQLException {
        String id = archetypeId.toLowerCase(Locale.ROOT);
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                int w = 0, t = 0, r = 0;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT wanted, for_trade, review FROM archetype_flags WHERE account_id = ? AND archetype_id = ?")) {
                    ps.setString(1, accountId);
                    ps.setString(2, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            w = rs.getInt(1);
                            t = rs.getInt(2);
                            r = rs.getInt(3);
                        }
                    }
                }
                if (wanted != null) w = wanted;
                if (forTrade != null) t = forTrade;
                if (review != null) r = review;
                if (w == 0 && t == 0 && r == 0) {
                    try (PreparedStatement del = c.prepareStatement(
                            "DELETE FROM archetype_flags WHERE account_id = ? AND archetype_id = ?")) {
                        del.setString(1, accountId);
                        del.setString(2, id);
                        del.executeUpdate();
                    }
                } else {
                    try (PreparedStatement up = c.prepareStatement(
                            "INSERT INTO archetype_flags (account_id, archetype_id, wanted, for_trade, review) VALUES (?,?,?,?,?) "
                                    + "ON CONFLICT(account_id, archetype_id) DO UPDATE SET wanted=excluded.wanted, "
                                    + "for_trade=excluded.for_trade, review=excluded.review")) {
                        up.setString(1, accountId);
                        up.setString(2, id);
                        up.setInt(3, w);
                        up.setInt(4, t);
                        up.setInt(5, r);
                        up.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    // ---- account settings (accounts.settings_json, attribute 10230) ------------------------------

    /** Merges {settingNumber: int} into the stored settings and returns the merged map. */
    public Map<String, Object> mergeAccountSettings(String accountId, Map<String, ?> incoming) throws SQLException {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                ObjectNode merged = JSON.createObjectNode();
                try (PreparedStatement ps = c.prepareStatement("SELECT settings_json FROM accounts WHERE account_id = ?")) {
                    ps.setString(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return Map.of();
                        String raw = rs.getString(1);
                        if (raw != null && !raw.isBlank()) {
                            try {
                                JsonNode n = JSON.readTree(raw);
                                if (n.isObject()) merged = (ObjectNode) n;
                            } catch (Exception ignored) {
                                // treat unreadable settings as empty
                            }
                        }
                    }
                }
                for (Map.Entry<String, ?> e : incoming.entrySet()) {
                    merged.put(String.valueOf(e.getKey()), Integer.parseInt(String.valueOf(e.getValue())));
                }
                try (PreparedStatement up = c.prepareStatement("UPDATE accounts SET settings_json = ? WHERE account_id = ?")) {
                    up.setString(1, merged.toString());
                    up.setString(2, accountId);
                    up.executeUpdate();
                }
                c.commit();
                return JSON.convertValue(merged, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        }
    }
}
