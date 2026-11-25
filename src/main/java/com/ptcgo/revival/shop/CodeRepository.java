package com.ptcgo.revival.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Redemption codes. Mirrors the code section of ptcgo/database/economy_data.py. A reward is
 * {"products": {archetype guid: count}, "coins": n, "gems": n, "tickets": n}; products land as non-tradable copies.
 */
public final class CodeRepository {
    private static final Logger LOG = LoggerFactory.getLogger(CodeRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** The only invalid-code string the client ships; the other two reasons are plain text. */
    public static final String CODE_INVALID = "shop.redeemcodes.invalidcode";
    public static final String CODE_EXPIRED = "This code has expired.";
    public static final String CODE_USED = "You have already used this code.";

    public record Code(String codeString, JsonNode reward, int maxUses, int currentUses, boolean enabled, String createdAt) {}

    /** {@code reason} is null on success; {@code reward} is null on failure. */
    public record Result(boolean ok, JsonNode reward, String reason) {
        static Result fail(String reason) {
            return new Result(false, null, reason);
        }
    }

    private final Database db;
    private final RandomGenerator rng;

    public CodeRepository(Database db, RandomGenerator rng) {
        this.db = db;
        this.rng = rng;
    }

    public String generateCodeString() {
        StringBuilder sb = new StringBuilder("SPIRIT");
        for (int part = 0; part < 3; part++) {
            sb.append('-');
            for (int i = 0; i < 4; i++) sb.append(CODE_ALPHABET.charAt(rng.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    public List<Code> list() throws SQLException {
        List<Code> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT code_string, reward_json, max_uses, current_uses, enabled, created_at FROM redemption_codes "
                        + "ORDER BY created_at DESC, code_string"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        }
        return out;
    }

    public Optional<Code> get(String codeString) throws SQLException {
        try (Connection c = db.connect()) {
            return Optional.ofNullable(read(c, codeString));
        }
    }

    private static Code read(Connection c, String codeString) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT code_string, reward_json, max_uses, current_uses, enabled, "
                + "created_at FROM redemption_codes WHERE code_string = ?")) {
            ps.setString(1, codeString);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    private static Code map(ResultSet rs) throws SQLException {
        JsonNode reward;
        try {
            reward = JSON.readTree(rs.getString(2));
        } catch (Exception e) {
            reward = JSON.createObjectNode();
        }
        return new Code(rs.getString(1), reward, rs.getInt(3), rs.getInt(4), rs.getBoolean(5), rs.getString(6));
    }

    /** Creates a code (auto-generating the string when blank); empty when the string already exists. */
    public Optional<Code> create(String codeString, JsonNode reward, int maxUses, boolean enabled) throws SQLException {
        String code = codeString == null || codeString.isBlank() ? generateCodeString() : codeString.strip().toUpperCase(Locale.ROOT);
        return Tx.run(db, c -> {
            if (read(c, code) != null) return Optional.<Code>empty();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO redemption_codes (code_string, reward_json, max_uses, enabled) VALUES (?,?,?,?)")) {
                ps.setString(1, code);
                ps.setString(2, (reward == null ? JSON.createObjectNode() : reward).toString());
                ps.setInt(3, maxUses);
                ps.setBoolean(4, enabled);
                ps.executeUpdate();
            }
            return Optional.of(read(c, code));
        });
    }

    public Optional<Code> update(String codeString, JsonNode reward, Integer maxUses, Boolean enabled) throws SQLException {
        return Tx.run(db, c -> {
            Code existing = read(c, codeString);
            if (existing == null) return Optional.<Code>empty();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE redemption_codes SET reward_json = ?, max_uses = ?, enabled = ? WHERE code_string = ?")) {
                ps.setString(1, (reward != null ? reward : existing.reward()).toString());
                ps.setInt(2, maxUses != null ? maxUses : existing.maxUses());
                ps.setBoolean(3, enabled != null ? enabled : existing.enabled());
                ps.setString(4, codeString);
                ps.executeUpdate();
            }
            return Optional.of(read(c, codeString));
        });
    }

    /** Removes the code and its redemption entries. */
    public boolean delete(String codeString) throws SQLException {
        return Tx.run(db, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM code_redemption_entries WHERE code_string = ?")) {
                ps.setString(1, codeString);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM redemption_codes WHERE code_string = ?")) {
                ps.setString(1, codeString);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static boolean alreadyUsed(Connection c, String code, String accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM code_redemption_entries WHERE code_string = ? AND account_id = ?")) {
            ps.setString(1, code);
            ps.setString(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Result validate(Connection c, Code code, String accountId) throws SQLException {
        if (code == null || !code.enabled()) return Result.fail(CODE_INVALID);
        if (code.maxUses() > 0 && code.currentUses() >= code.maxUses()) return Result.fail(CODE_EXPIRED);
        if (alreadyUsed(c, code.codeString(), accountId)) return Result.fail(CODE_USED);
        return new Result(true, code.reward(), null);
    }

    /** Validates a code for an account without redeeming it. */
    public Result check(String codeString, String accountId) throws SQLException {
        try (Connection c = db.connect()) {
            return validate(c, read(c, codeString.strip().toUpperCase(Locale.ROOT)), accountId);
        }
    }

    /** Atomically redeems a code and grants its reward; any failure leaves nothing changed. */
    public Result redeem(String codeString, String accountId) {
        String code = codeString.strip().toUpperCase(Locale.ROOT);
        try {
            return Tx.run(db, c -> {
                Result v = validate(c, read(c, code), accountId);
                if (!v.ok()) throw new Tx.Rollback(v);
                try (PreparedStatement ps = c.prepareStatement("UPDATE redemption_codes SET current_uses = current_uses + 1 WHERE code_string = ?")) {
                    ps.setString(1, code);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO code_redemption_entries (code_string, account_id) VALUES (?,?)")) {
                    ps.setString(1, code);
                    ps.setString(2, accountId);
                    ps.executeUpdate();
                }
                grant(c, accountId, v.reward());
                return v;
            });
        } catch (Exception e) {
            LOG.error("[Economy] Failed to redeem code {} for {}: {}", code, accountId, e.toString());
            return Result.fail(CODE_INVALID);
        }
    }

    /** Applies a reward inside an open transaction. */
    static void grant(Connection c, String accountId, JsonNode reward) throws SQLException {
        var products = reward.path("products").fields();
        while (products.hasNext()) {
            var e = products.next();
            Tx.addCopies(c, accountId, e.getKey(), 0, e.getValue().asInt());
        }
        long coins = reward.path("coins").asLong(0), gems = reward.path("gems").asLong(0), tickets = reward.path("tickets").asLong(0);
        if (coins != 0 || gems != 0 || tickets != 0) {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO wallets (account_id, coins, gems, tickets) VALUES (?,0,0,0)")) {
                ps.setString(1, accountId);
                ps.executeUpdate();
            }
            int max = PlayerRepository.WALLET_MAX;
            try (PreparedStatement ps = c.prepareStatement("UPDATE wallets SET coins = MIN(MAX(coins + ?, 0), " + max
                    + "), gems = MIN(MAX(gems + ?, 0), " + max + "), tickets = MIN(MAX(tickets + ?, 0), " + max + ") WHERE account_id = ?")) {
                ps.setLong(1, coins);
                ps.setLong(2, gems);
                ps.setLong(3, tickets);
                ps.setString(4, accountId);
                ps.executeUpdate();
            }
        }
    }
}
