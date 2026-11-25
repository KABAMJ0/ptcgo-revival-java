package com.ptcgo.revival.db;

import java.sql.SQLException;
import java.sql.Statement;

/** Tournament tables: same schema as the Python SQLAlchemy models (ptcgo/database/models/tournaments.py). */
final class TournamentSchema {
    private TournamentSchema() {}

    static void create(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS async_tournaments (
                    tournament_id VARCHAR NOT NULL PRIMARY KEY,
                    definition_json TEXT NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS tournament_entries (
                    entry_id VARCHAR NOT NULL PRIMARY KEY,
                    tournament_id VARCHAR NOT NULL REFERENCES async_tournaments (tournament_id),
                    account_id VARCHAR NOT NULL,
                    deck_json TEXT,
                    wins INTEGER NOT NULL DEFAULT 0,
                    losses INTEGER NOT NULL DEFAULT 0,
                    tiebreakers INTEGER NOT NULL DEFAULT 0,
                    status VARCHAR NOT NULL DEFAULT 'active',
                    rewards_claimed BOOLEAN NOT NULL DEFAULT 0,
                    history_json TEXT,
                    last_update BIGINT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS ix_tournament_entries_tournament_id ON tournament_entries (tournament_id)");
        st.execute("CREATE INDEX IF NOT EXISTS ix_tournament_entries_account_id ON tournament_entries (account_id)");
        st.execute("""
                CREATE TABLE IF NOT EXISTS tournament_leaderboard_claims (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tournament_id VARCHAR NOT NULL,
                    account_id VARCHAR NOT NULL,
                    rank INTEGER NOT NULL DEFAULT 0,
                    claimed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (tournament_id, account_id)
                )""");
    }
}
