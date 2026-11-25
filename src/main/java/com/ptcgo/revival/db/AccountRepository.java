package com.ptcgo.revival.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AccountRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLS =
            "id, account_id, username, password_hash, screen_name, is_admin, is_banned, settings_json, created_at";
    private final Database db;

    public AccountRepository(Database db) {
        this.db = db;
    }

    /** Unsalted SHA-256 hex, kept identical to the Python server so existing accounts keep working. */
    public static String hashPassword(String password) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verifyPassword(String storedHash, String provided) {
        return MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8), hashPassword(provided).getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Account> findByUsername(String username) throws SQLException {
        return queryOne("SELECT " + COLS + " FROM accounts WHERE lower(username) = lower(?)", username);
    }

    public Optional<Account> findById(String accountId) throws SQLException {
        return queryOne("SELECT " + COLS + " FROM accounts WHERE account_id = ?", accountId);
    }

    /** Returns empty if the username is taken. */
    public Optional<Account> create(String username, String password, boolean admin) throws SQLException {
        String accountId = UUID.randomUUID().toString();
        try (Connection c = db.connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO accounts (account_id, username, password_hash, screen_name, is_admin) VALUES (?,?,?,?,?)")) {
            ps.setString(1, accountId);
            ps.setString(2, username);
            ps.setString(3, hashPassword(password));
            ps.setString(4, username);
            ps.setBoolean(5, admin);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) return Optional.empty();
            throw e;
        }
        return findById(accountId);
    }

    private Optional<Account> queryOne(String sql, String arg) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static Account map(ResultSet rs) throws SQLException {
        Map<String, Object> settings = Map.of();
        String raw = rs.getString("settings_json");
        if (raw != null && !raw.isBlank()) {
            try {
                settings = JSON.readValue(raw, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                // malformed settings are treated as empty
            }
        }
        return new Account(rs.getLong("id"), rs.getString("account_id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("screen_name"), rs.getBoolean("is_admin"),
                rs.getBoolean("is_banned"), settings, rs.getString("created_at"));
    }
}
