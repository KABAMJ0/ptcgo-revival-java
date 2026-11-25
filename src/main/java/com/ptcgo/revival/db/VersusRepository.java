package com.ptcgo.revival.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.progression.Seasons;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Versus ladder points and tier rewards (ptcgo/database/versus_data.py). */
public final class VersusRepository {
    public static final int POINTS_PER_WIN = 5;
    public static final int POINTS_PER_LOSS = 0;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database db;

    public VersusRepository(Database db) {
        this.db = db;
    }

    public record Progress(int points, int allTimePoints) {}

    public record Award(int points, int allTimePoints, List<JsonNode> granted) {}

    /** Points are 0 when the stored season isn't {@code seasonId} (the ladder resets per season). */
    public Progress progress(String accountId, String seasonId) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT season_id, points, all_time_points FROM versus_progress WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new Progress(0, 0);
                return rs.getString(1).equals(seasonId) ? new Progress(rs.getInt(2), rs.getInt(3)) : new Progress(0, rs.getInt(3));
            }
        }
    }

    /** Adds ladder points for a finished match and grants every newly crossed tier threshold, in one transaction. */
    public Award awardMatchPoints(String accountId, boolean won, Seasons.Season season) throws SQLException {
        int delta = won ? POINTS_PER_WIN : POINTS_PER_LOSS;
        String seasonId = season == null ? "" : season.seasonId();
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                String storedSeason = "";
                int points = 0, allTime = 0;
                ObjectNode granted = JSON.createObjectNode();
                boolean exists = false;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT season_id, points, all_time_points, granted_json FROM versus_progress WHERE account_id = ?")) {
                    ps.setString(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            exists = true;
                            storedSeason = rs.getString(1);
                            points = rs.getInt(2);
                            allTime = rs.getInt(3);
                            JsonNode g = JSON.readTree(rs.getString(4));
                            if (g.isObject()) granted = (ObjectNode) g;
                        }
                    }
                }
                if (!storedSeason.equals(seasonId)) {
                    points = 0;
                    granted = JSON.createObjectNode();
                }
                points += delta;
                allTime += delta;
                List<JsonNode> newlyGranted = new ArrayList<>();
                if (season != null) {
                    for (Map.Entry<Integer, List<JsonNode>> e : season.thresholds().entrySet()) {
                        if (e.getKey() <= points && !granted.has(String.valueOf(e.getKey()))) {
                            grantRewards(c, accountId, e.getValue());
                            granted.put(String.valueOf(e.getKey()), true);
                            newlyGranted.addAll(e.getValue());
                        }
                    }
                }
                try (PreparedStatement up = c.prepareStatement(
                        "INSERT INTO versus_progress (account_id, season_id, points, all_time_points, granted_json) "
                                + "VALUES (?,?,?,?,?) ON CONFLICT(account_id) DO UPDATE SET season_id=excluded.season_id, "
                                + "points=excluded.points, all_time_points=excluded.all_time_points, granted_json=excluded.granted_json")) {
                    up.setString(1, accountId);
                    up.setString(2, seasonId);
                    up.setInt(3, points);
                    up.setInt(4, allTime);
                    up.setString(5, granted.toString());
                    up.executeUpdate();
                }
                c.commit();
                return new Award(points, allTime, newlyGranted);
            } catch (SQLException | java.io.IOException | RuntimeException ex) {
                c.rollback();
                if (ex instanceof SQLException sql) throw sql;
                throw new SQLException(ex);
            }
        }
    }

    /** Cards go to the non-tradable collection; Tokens/gems/tickets to the wallet (by rewardCurrency). */
    static void grantRewards(Connection c, String accountId, List<JsonNode> rewards) throws SQLException {
        for (JsonNode r : rewards) {
            String type = r.path("rewardType").asText();
            int amount = r.path("rewardAmount").asInt(0);
            if (type.equals("Archetype")) {
                String guid = r.path("rewardProductID").asText("").toLowerCase(Locale.ROOT);
                if (guid.isEmpty()) continue;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO collection (account_id, archetype_id, tradable_count, nontradable_count) VALUES (?,?,0,?) "
                                + "ON CONFLICT(account_id, archetype_id) DO UPDATE SET nontradable_count = nontradable_count + excluded.nontradable_count")) {
                    ps.setString(1, accountId);
                    ps.setString(2, guid);
                    ps.setInt(3, Math.max(1, amount));
                    ps.executeUpdate();
                }
            } else if (type.equals("Tokens") && amount > 0) {
                String currency = r.path("rewardCurrency").asText("").toLowerCase(Locale.ROOT);
                String col = currency.contains("gem") ? "gems" : currency.contains("ticket") ? "tickets" : "coins";
                addToWallet(c, accountId, col, amount);
            }
        }
    }

    static void addToWallet(Connection c, String accountId, String column, int amount) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT OR IGNORE INTO wallets (account_id, coins, gems, tickets) VALUES (?,?,?,?)")) {
            // A wallet that doesn't exist yet starts from the normal defaults (login creates it on the Python server).
            ins.setString(1, accountId);
            ins.setInt(2, PlayerRepository.STARTING_COINS);
            ins.setInt(3, PlayerRepository.STARTING_GEMS);
            ins.setInt(4, PlayerRepository.STARTING_TICKETS);
            ins.executeUpdate();
        }
        try (PreparedStatement up = c.prepareStatement(
                "UPDATE wallets SET " + column + " = MIN(" + PlayerRepository.WALLET_MAX + ", " + column + " + ?) WHERE account_id = ?")) {
            up.setInt(1, amount);
            up.setString(2, accountId);
            up.executeUpdate();
        }
    }
}
