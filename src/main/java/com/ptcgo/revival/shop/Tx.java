package com.ptcgo.revival.shop;

import com.ptcgo.revival.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One SQLite write transaction ({@code BEGIN IMMEDIATE}, the write lock Python takes first), so two racing purchases
 * serialise. A body that throws {@link Rollback} undoes everything and returns that value instead.
 */
final class Tx {
    private Tx() {}

    @FunctionalInterface
    interface Body<T> {
        T run(Connection c) throws SQLException;
    }

    /** Roll back and make {@link #run} return {@code value}. */
    static final class Rollback extends RuntimeException {
        final transient Object value;

        Rollback(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T run(Database db, Body<T> body) throws SQLException {
        try (Connection c = db.connect()) {
            try (Statement st = c.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            }
            try {
                T result = body.run(c);
                try (Statement st = c.createStatement()) {
                    st.execute("COMMIT");
                }
                return result;
            } catch (Rollback r) {
                rollback(c);
                return (T) r.value;
            } catch (SQLException | RuntimeException e) {
                rollback(c);
                throw e;
            }
        }
    }

    private static void rollback(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // nothing left to undo
        }
    }

    /** Adds copies of one archetype (upsert), tradable and/or non-tradable. */
    static void addCopies(Connection c, String accountId, String archetypeId, int tradable, int nontradable) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO collection (account_id, archetype_id, tradable_count, nontradable_count) VALUES (?,?,?,?) "
                        + "ON CONFLICT(account_id, archetype_id) DO UPDATE SET tradable_count = tradable_count + excluded.tradable_count, "
                        + "nontradable_count = nontradable_count + excluded.nontradable_count")) {
            ps.setString(1, accountId);
            ps.setString(2, archetypeId);
            ps.setInt(3, tradable);
            ps.setInt(4, nontradable);
            ps.executeUpdate();
        }
    }

    static boolean isUniqueViolation(SQLException e) {
        String m = e.getMessage();
        return m != null && (m.contains("UNIQUE") || m.contains("PRIMARY KEY"));
    }
}
