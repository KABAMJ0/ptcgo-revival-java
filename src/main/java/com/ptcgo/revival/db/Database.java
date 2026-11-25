package com.ptcgo.revival.db;

import com.ptcgo.revival.Config;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite access. Opens the same file the Python server uses (WAL, 30 s busy timeout), so an existing
 * ptcgo_server.db can be reused. A connection is opened per operation; SQLite connections are cheap.
 */
public final class Database {
    private final SQLiteDataSource ds;

    public Database(Config cfg) {
        this("jdbc:sqlite:" + cfg.dbPath.toAbsolutePath());
    }

    public Database(String jdbcUrl) {
        SQLiteConfig c = new SQLiteConfig();
        c.setJournalMode(SQLiteConfig.JournalMode.WAL);
        c.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        c.setBusyTimeout(30_000);
        c.setTempStore(SQLiteConfig.TempStore.MEMORY);
        ds = new SQLiteDataSource(c);
        ds.setUrl(jdbcUrl);
    }

    public Connection connect() throws SQLException {
        return ds.getConnection();
    }

    /** Creates missing tables. Existing Python-created tables are left untouched. */
    public void migrate() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        account_id VARCHAR NOT NULL UNIQUE,
                        username VARCHAR NOT NULL UNIQUE,
                        password_hash VARCHAR NOT NULL,
                        screen_name VARCHAR,
                        is_admin BOOLEAN NOT NULL DEFAULT 0,
                        is_banned BOOLEAN NOT NULL DEFAULT 0,
                        settings_json TEXT,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS wallets (
                        account_id VARCHAR NOT NULL PRIMARY KEY REFERENCES accounts (account_id),
                        coins INTEGER NOT NULL DEFAULT 1000,
                        gems INTEGER NOT NULL DEFAULT 0,
                        tickets INTEGER NOT NULL DEFAULT 100
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS decks (
                        id VARCHAR NOT NULL PRIMARY KEY,
                        account_id VARCHAR NOT NULL REFERENCES accounts (account_id),
                        name VARCHAR NOT NULL,
                        deck_data TEXT NOT NULL,
                        is_avatar BOOLEAN NOT NULL DEFAULT 0,
                        overall_wins INTEGER NOT NULL DEFAULT 0,
                        overall_played INTEGER NOT NULL DEFAULT 0,
                        wins_since_last_edit INTEGER NOT NULL DEFAULT 0,
                        played_since_last_edit INTEGER NOT NULL DEFAULT 0
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS ix_decks_account_id ON decks (account_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS collection (
                        account_id VARCHAR NOT NULL REFERENCES accounts (account_id),
                        archetype_id VARCHAR NOT NULL,
                        tradable_count INTEGER NOT NULL DEFAULT 0,
                        nontradable_count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (account_id, archetype_id)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS archetype_flags (
                        account_id VARCHAR NOT NULL REFERENCES accounts (account_id),
                        archetype_id VARCHAR NOT NULL,
                        wanted INTEGER NOT NULL DEFAULT 0,
                        for_trade INTEGER NOT NULL DEFAULT 0,
                        review INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (account_id, archetype_id)
                    )""");
            // Social + trade tables: same schema as the Python SQLAlchemy models (friends, trade_offers).
            st.execute("""
                    CREATE TABLE IF NOT EXISTS friends (
                        account_id VARCHAR NOT NULL REFERENCES accounts (account_id),
                        friend_id VARCHAR NOT NULL REFERENCES accounts (account_id),
                        status INTEGER NOT NULL,
                        PRIMARY KEY (account_id, friend_id)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS ix_friends_friend_id ON friends (friend_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS trade_offers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        offer_id VARCHAR NOT NULL UNIQUE,
                        sender_id VARCHAR NOT NULL,
                        recipient_id VARCHAR,
                        offering_json TEXT NOT NULL,
                        requesting_json TEXT NOT NULL,
                        status VARCHAR NOT NULL DEFAULT 'open',
                        accepted_by VARCHAR,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS ix_trade_offers_sender_id ON trade_offers (sender_id)");
            st.execute("CREATE INDEX IF NOT EXISTS ix_trade_offers_recipient_id ON trade_offers (recipient_id)");
            st.execute("CREATE INDEX IF NOT EXISTS ix_trade_offers_status_created ON trade_offers (status, created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS ix_trade_offers_status_recipient ON trade_offers (status, recipient_id)");
            st.execute("CREATE INDEX IF NOT EXISTS ix_trade_offers_status_sender ON trade_offers (status, sender_id)");
        }
    }
}
