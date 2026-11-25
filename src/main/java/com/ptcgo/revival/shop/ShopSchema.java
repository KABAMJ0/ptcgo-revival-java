package com.ptcgo.revival.shop;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tables for the shop, redemption codes and product claims. Same column names and constraints as the Python
 * SQLAlchemy models in ptcgo/database/models/economy.py, so an existing Python database is used as is
 * ({@code IF NOT EXISTS} never alters an existing table).
 */
public final class ShopSchema {
    private ShopSchema() {}

    public static void create(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS shop_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_guid VARCHAR NOT NULL UNIQUE,
                    display_name VARCHAR NOT NULL DEFAULT '',
                    currency INTEGER NOT NULL DEFAULT -1784319558,
                    price INTEGER NOT NULL DEFAULT 200,
                    enabled BOOLEAN NOT NULL DEFAULT 1,
                    featured BOOLEAN NOT NULL DEFAULT 0,
                    top_selling BOOLEAN NOT NULL DEFAULT 0,
                    sort_order INTEGER NOT NULL DEFAULT 0
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS redemption_codes (
                    code_string VARCHAR NOT NULL PRIMARY KEY,
                    reward_json TEXT NOT NULL,
                    max_uses INTEGER NOT NULL DEFAULT 1,
                    current_uses INTEGER NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS code_redemption_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code_string VARCHAR NOT NULL REFERENCES redemption_codes (code_string),
                    account_id VARCHAR NOT NULL,
                    redeemed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (code_string, account_id)
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS product_claims (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    account_id VARCHAR NOT NULL,
                    product_guid VARCHAR NOT NULL,
                    claimed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uq_product_claim UNIQUE (account_id, product_guid)
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS ix_product_claims_account_id ON product_claims (account_id)");
    }
}
