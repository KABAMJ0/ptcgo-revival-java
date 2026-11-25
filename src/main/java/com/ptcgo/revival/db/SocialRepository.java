package com.ptcgo.revival.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The {@code friends} table. Mirrors ptcgo/database/social.py (one row per direction). */
public final class SocialRepository {
    /** FriendListStatus in the Python server. Only INVITED and FRIENDS are ever written. */
    public static final int INVITED = 0;
    public static final int REMOVED = 1;
    public static final int BLOCKING = 2;
    public static final int BLOCKED = 3;
    public static final int FRIENDS = 4;
    public static final int NO_RELATIONSHIP = 5;

    /** A row of the account's own friend list, joined with the other account's name. */
    public record FriendRow(String friendId, String displayName, int status) {}

    private final Database db;

    public SocialRepository(Database db) {
        this.db = db;
    }

    /** Every row where {@code accountId} is the owner (invited and confirmed), joined with accounts like the Python query. */
    public List<FriendRow> friends(String accountId) throws SQLException {
        List<FriendRow> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT f.friend_id, a.screen_name, a.username, f.status FROM friends f "
                        + "JOIN accounts a ON a.account_id = f.friend_id WHERE f.account_id = ? ORDER BY f.rowid")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String screen = rs.getString(2);
                    out.add(new FriendRow(rs.getString(1), screen != null && !screen.isBlank() ? screen : rs.getString(3),
                            rs.getInt(4)));
                }
            }
        }
        return out;
    }

    /** Status of the row {@code accountId -> friendId}. */
    public record Invite(String fromAccountId, String displayName) {}

    /** Pending invitations sent TO this account (status 0 = Invited), newest rows in table order. */
    public List<Invite> incomingInvites(String accountId) throws SQLException {
        List<Invite> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT f.account_id, COALESCE(a.screen_name, a.username) FROM friends f "
                        + "JOIN accounts a ON a.account_id = f.account_id WHERE f.friend_id = ? AND f.status = 0")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Invite(rs.getString(1), rs.getString(2)));
            }
        }
        return out;
    }

    public Optional<Integer> status(String accountId, String friendId) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT status FROM friends WHERE account_id = ? AND friend_id = ?")) {
            ps.setString(1, accountId);
            ps.setString(2, friendId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
            }
        }
    }

    /** Adds or updates one directed relationship. */
    public void upsert(String accountId, String friendId, int status) throws SQLException {
        try (Connection c = db.connect()) {
            upsert(c, accountId, friendId, status);
        }
    }

    private static void upsert(Connection c, String accountId, String friendId, int status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO friends (account_id, friend_id, status) VALUES (?,?,?) "
                        + "ON CONFLICT(account_id, friend_id) DO UPDATE SET status = excluded.status")) {
            ps.setString(1, accountId);
            ps.setString(2, friendId);
            ps.setInt(3, status);
            ps.executeUpdate();
        }
    }

    /** Both directions to FRIENDS in one transaction. */
    public void befriend(String a, String b) throws SQLException {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                upsert(c, a, b, FRIENDS);
                upsert(c, b, a, FRIENDS);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Deletes both directions in one transaction; true if any row existed. */
    public boolean removeBoth(String a, String b) throws SQLException {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                int n = 0;
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM friends WHERE account_id = ? AND friend_id = ?")) {
                    for (String[] pair : new String[][] {{a, b}, {b, a}}) {
                        ps.setString(1, pair[0]);
                        ps.setString(2, pair[1]);
                        n += ps.executeUpdate();
                    }
                }
                c.commit();
                return n > 0;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Case-insensitive screen-name lookup (the username lookup is {@link AccountRepository#findByUsername}). */
    public Optional<String> accountIdByScreenName(String screenName) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT account_id FROM accounts WHERE lower(screen_name) = lower(?) ORDER BY id LIMIT 1")) {
            ps.setString(1, screenName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }
}
