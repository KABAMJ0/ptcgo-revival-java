package com.ptcgo.revival.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tournament persistence. Port of ptcgo/database/tournament_data.py: async tournament definitions, runs
 * (entries), leaderboard claims, and the fee / prize helpers the live (Events scene) brackets use. Every
 * multi-step operation (fee debit + entry insert, prize grant + claimed flag) is one transaction.
 */
public final class TournamentRepository {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** JoinAsyncTournament currency string (lower-case) to wallet column. */
    public static final Map<String, String> CURRENCY_FIELDS = Map.of(
            "tokens", "coins", "coins", "coins", "gems", "gems",
            "tickets", "tickets", "tournamentticket", "tickets");

    private final Database db;

    public TournamentRepository(Database db) {
        this.db = db;
    }

    public record Row(String tournamentId, JsonNode definition, boolean enabled) {}

    public record Entry(String entryId, String tournamentId, String accountId, JsonNode deck, int wins, int losses,
                        int tiebreakers, String status, boolean rewardsClaimed, JsonNode history, long lastUpdate) {}

    /** {@code error} is the client-facing text when the entry was not created. */
    public record EntryResult(Entry entry, String error) {}

    public record GameRecorded(Entry entry, boolean runComplete) {}

    public record Finished(Entry entry, List<JsonNode> granted) {}

    /** {@code rank} is null (and {@code error} set) when nothing was granted. */
    public record Claim(Integer rank, List<JsonNode> granted, String error) {}

    public static long nowMs() {
        return System.currentTimeMillis();
    }

    // ---- transactions ----------------------------------------------------------------------------

    @FunctionalInterface
    private interface Body<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T tx(Body<T> body) throws SQLException {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                T r = body.run(c);
                c.commit();
                return r;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ---- JSON helpers ----------------------------------------------------------------------------

    /** JSONEncodedDict.process_result_value: unreadable or null text reads as an empty value. */
    private static JsonNode readJson(String text, boolean array) {
        if (text != null) {
            try {
                JsonNode n = JSON.readTree(text);
                if (n != null && !n.isMissingNode() && !n.isNull()) return n;
            } catch (Exception ignored) {
                // falls through to the empty value
            }
        }
        return array ? JsonNodeFactory.instance.arrayNode() : JsonNodeFactory.instance.objectNode();
    }

    private static long intOr(JsonNode n, String field, long dflt) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) return dflt;
        long x = v.isNumber() ? v.asLong() : parseLong(v.asText(""), 0);
        return x == 0 ? dflt : x;
    }

    private static long parseLong(String s, long dflt) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        return new Entry(rs.getString("entry_id"), rs.getString("tournament_id"), rs.getString("account_id"),
                readJson(rs.getString("deck_json"), false), rs.getInt("wins"), rs.getInt("losses"),
                rs.getInt("tiebreakers"), rs.getString("status"), rs.getBoolean("rewards_claimed"),
                readJson(rs.getString("history_json"), true), rs.getLong("last_update"));
    }

    // ---- definitions -----------------------------------------------------------------------------

    public List<Row> listTournaments(boolean enabledOnly) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT tournament_id, definition_json, enabled FROM async_tournaments"
                        + (enabledOnly ? " WHERE enabled = 1" : "") + " ORDER BY created_at, rowid");
             ResultSet rs = ps.executeQuery()) {
            List<Row> out = new ArrayList<>();
            while (rs.next()) out.add(new Row(rs.getString(1), readJson(rs.getString(2), false), rs.getBoolean(3)));
            return out;
        }
    }

    /** Insert or update; null {@code definition} / {@code enabled} keep the stored value. */
    public Row upsertTournament(JsonNode definition, String tournamentId, Boolean enabled) throws SQLException {
        return tx(c -> {
            Row existing = null;
            if (tournamentId != null && !tournamentId.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT tournament_id, definition_json, enabled FROM async_tournaments WHERE tournament_id = ?")) {
                    ps.setString(1, tournamentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) existing = new Row(rs.getString(1), readJson(rs.getString(2), false), rs.getBoolean(3));
                    }
                }
            }
            if (existing == null) {
                String id = tournamentId != null && !tournamentId.isEmpty() ? tournamentId : UUID.randomUUID().toString();
                JsonNode def = definition == null ? JsonNodeFactory.instance.objectNode() : definition;
                boolean en = enabled == null || enabled;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO async_tournaments (tournament_id, definition_json, enabled) VALUES (?,?,?)")) {
                    ps.setString(1, id);
                    ps.setString(2, def.toString());
                    ps.setBoolean(3, en);
                    ps.executeUpdate();
                }
                return new Row(id, def, en);
            }
            JsonNode def = definition != null ? definition : existing.definition();
            boolean en = enabled != null ? enabled : existing.enabled();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE async_tournaments SET definition_json = ?, enabled = ? WHERE tournament_id = ?")) {
                ps.setString(1, def.toString());
                ps.setBoolean(2, en);
                ps.setString(3, existing.tournamentId());
                ps.executeUpdate();
            }
            return new Row(existing.tournamentId(), def, en);
        });
    }

    /** Deletes the tournament with its entries and claims; false when it did not exist. */
    public boolean deleteTournament(String tournamentId) throws SQLException {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM async_tournaments WHERE tournament_id = ?")) {
                ps.setString(1, tournamentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                }
            }
            for (String table : new String[] {"tournament_entries", "tournament_leaderboard_claims", "async_tournaments"}) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE tournament_id = ?")) {
                    ps.setString(1, tournamentId);
                    ps.executeUpdate();
                }
            }
            return true;
        });
    }

    // ---- entries / runs --------------------------------------------------------------------------

    public List<Entry> activeEntries(String accountId) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM tournament_entries WHERE account_id = ? AND status = 'active' ORDER BY rowid")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Entry> out = new ArrayList<>();
                while (rs.next()) out.add(entry(rs));
                return out;
            }
        }
    }

    /** The entry, or null. */
    public Entry getEntry(String entryId) throws SQLException {
        try (Connection c = db.connect()) {
            return getEntry(c, entryId);
        }
    }

    private static Entry getEntry(Connection c, String entryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM tournament_entries WHERE entry_id = ?")) {
            ps.setString(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? entry(rs) : null;
            }
        }
    }

    public int countRuns(String accountId, String tournamentId) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM tournament_entries WHERE account_id = ? AND tournament_id = ?")) {
            ps.setString(1, accountId);
            ps.setString(2, tournamentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Joins a run atomically: fee check + deduction + entry creation. The fee row is picked by {@code currency}
     * (case-insensitive) among {@code run.entryFee}; no fee rows means a free tournament.
     */
    public EntryResult createEntry(String accountId, String tournamentId, JsonNode definition, String currency,
                                   JsonNode deck) throws SQLException {
        JsonNode fees = definition == null ? null : definition.path("run").path("entryFee");
        long maxRuns = intOr(definition, "maxRuns", 0);
        return tx(c -> {
            int total = 0;
            boolean active = false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status FROM tournament_entries WHERE account_id = ? AND tournament_id = ?")) {
                ps.setString(1, accountId);
                ps.setString(2, tournamentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        total++;
                        if ("active".equals(rs.getString(1))) active = true;
                    }
                }
            }
            if (active) return new EntryResult(null, "You already have an active run in this tournament.");
            if (maxRuns > 0 && total >= maxRuns) return new EntryResult(null, "No runs remaining for this tournament.");

            if (fees != null && fees.isArray() && fees.size() > 0) {
                String wanted = (currency == null ? "" : currency).toLowerCase(Locale.ROOT);
                JsonNode fee = null;
                for (JsonNode f : fees) {
                    if (f.path("currency").asText("").toLowerCase(Locale.ROOT).equals(wanted)) {
                        fee = f;
                        break;
                    }
                }
                if (fee == null) return new EntryResult(null, "Invalid entry fee currency.");
                String field = CURRENCY_FIELDS.get(fee.path("currency").asText("").toLowerCase(Locale.ROOT));
                long amount = intOr(fee, "amount", 0);
                if (field == null) return new EntryResult(null, "Invalid entry fee currency.");
                if (amount > 0) {
                    ensureWallet(c, accountId);
                    if (readWalletColumn(c, accountId, field) < amount) {
                        return new EntryResult(null, "You cannot afford the entry fee.");
                    }
                    addToWalletColumn(c, accountId, field, -amount);
                }
            }

            String entryId = UUID.randomUUID().toString();
            long now = nowMs();
            JsonNode d = deck == null ? JsonNodeFactory.instance.objectNode() : deck;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tournament_entries (entry_id, tournament_id, account_id, deck_json, history_json, last_update) "
                            + "VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, entryId);
                ps.setString(2, tournamentId);
                ps.setString(3, accountId);
                ps.setString(4, d.toString());
                ps.setString(5, "[]");
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return new EntryResult(getEntry(c, entryId), null);
        });
    }

    public boolean updateEntryDeck(String entryId, String accountId, JsonNode deck) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE tournament_entries SET deck_json = ?, last_update = ? "
                        + "WHERE entry_id = ? AND account_id = ? AND status = 'active'")) {
            ps.setString(1, (deck == null ? JsonNodeFactory.instance.objectNode() : deck).toString());
            ps.setLong(2, nowMs());
            ps.setString(3, entryId);
            ps.setString(4, accountId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Applies one game result to a run; null when the run is unknown or no longer active. {@code runComplete} is set when
     * the run reached its maxWins / maxLosses / maxGames cap.
     */
    public GameRecorded recordGameResult(String entryId, boolean won, String opponentId, String opponentName,
                                         JsonNode runConfig) throws SQLException {
        return tx(c -> {
            Entry e = getEntry(c, entryId);
            if (e == null || !"active".equals(e.status())) return null;
            int wins = e.wins() + (won ? 1 : 0);
            int losses = e.losses() + (won ? 0 : 1);
            ArrayNode history = JsonNodeFactory.instance.arrayNode();
            e.history().forEach(history::add);
            ObjectNode h = history.addObject();
            h.put("opponentID", opponentId);
            h.put("opponentName", opponentName);
            h.put("gameResult", won ? "Win" : "Loss");
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tournament_entries SET wins = ?, losses = ?, history_json = ?, last_update = ? WHERE entry_id = ?")) {
                ps.setInt(1, wins);
                ps.setInt(2, losses);
                ps.setString(3, history.toString());
                ps.setLong(4, nowMs());
                ps.setString(5, entryId);
                ps.executeUpdate();
            }
            long maxWins = intOr(runConfig, "maxWins", 0);
            long maxLosses = intOr(runConfig, "maxLosses", 0);
            long maxGames = intOr(runConfig, "maxGames", 0);
            boolean complete = (maxWins > 0 && wins >= maxWins) || (maxLosses > 0 && losses >= maxLosses)
                    || (maxGames > 0 && wins + losses >= maxGames);
            return new GameRecorded(getEntry(c, entryId), complete);
        });
    }

    /** Rewards of the first prize row whose [start, end] contains {@code value}. */
    public static List<JsonNode> prizeRewardsFor(JsonNode prizeTable, long value) {
        List<JsonNode> out = new ArrayList<>();
        if (prizeTable != null && prizeTable.isArray()) {
            for (JsonNode prize : prizeTable) {
                if (prize.path("start").asLong(0) <= value && value <= prize.path("end").asLong(0)) {
                    JsonNode rewards = prize.path("rewards");
                    if (rewards.isArray()) rewards.forEach(out::add);
                    return out;
                }
            }
        }
        return out;
    }

    /**
     * Ends a run and grants its prize-table rewards once (idempotent). Null when the entry does not exist or belongs to
     * someone else.
     */
    public Finished finishEntry(String entryId, String accountId, JsonNode definition, boolean resigned)
            throws SQLException {
        JsonNode prizeTable = definition == null ? null : definition.path("run").path("prizeTable");
        return tx(c -> {
            Entry e = getEntry(c, entryId);
            if (e == null || !e.accountId().equals(accountId)) return null;
            List<JsonNode> granted = new ArrayList<>();
            if (!e.rewardsClaimed()) {
                granted = prizeRewardsFor(prizeTable, e.wins());
                if (!granted.isEmpty()) VersusRepository.grantRewards(c, accountId, granted);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tournament_entries SET rewards_claimed = 1, last_update = ?, "
                            + "status = CASE WHEN status = 'active' THEN ? ELSE status END WHERE entry_id = ?")) {
                ps.setLong(1, nowMs());
                ps.setString(2, resigned ? "resigned" : "complete");
                ps.setString(3, entryId);
                ps.executeUpdate();
            }
            return new Finished(getEntry(c, entryId), granted);
        });
    }

    // ---- leaderboard -----------------------------------------------------------------------------

    /** One leaderboard row (the AsyncTournamentStanding wire shape). */
    public static ObjectNode standing(String accountId, String displayName, double points, int rank) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("accountID", accountId);
        n.put("displayName", displayName);
        n.put("points", points);
        n.put("rank", rank);
        n.put("displayRank", String.valueOf(rank));
        return n;
    }

    /** All participants ranked by wins * winValue + losses * lossValue (descending, ties in account-id order). */
    public List<ObjectNode> leaderboardStandings(String tournamentId, JsonNode definition) throws SQLException {
        JsonNode lb = definition == null ? null : definition.path("leaderboard");
        long winValue = intOr(lb, "winValue", 3);
        long lossValue = intOr(lb, "lossValue", 0);
        record Agg(String accountId, long wins, long losses, String name) {}
        List<Agg> rows = new ArrayList<>();
        try (Connection c = db.connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT t.account_id, SUM(t.wins), SUM(t.losses), COALESCE(NULLIF(a.screen_name, ''), a.username) "
                            + "FROM tournament_entries t LEFT JOIN accounts a ON a.account_id = t.account_id "
                            + "WHERE t.tournament_id = ? GROUP BY t.account_id ORDER BY t.account_id")) {
                ps.setString(1, tournamentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Agg(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)));
                }
            }
        }
        List<double[]> points = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            points.add(new double[] {rows.get(i).wins() * winValue + rows.get(i).losses() * lossValue});
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(points.get(b)[0], points.get(a)[0])); // stable
        List<ObjectNode> out = new ArrayList<>();
        int rank = 1;
        for (int idx : order) {
            Agg a = rows.get(idx);
            out.add(standing(a.accountId(), a.name() == null ? "Unknown" : a.name(), points.get(idx)[0], rank++));
        }
        return out;
    }

    /** {tournamentID: true} for every leaderboard the account has claimed. */
    public List<String> claimedLeaderboards(String accountId) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT tournament_id FROM tournament_leaderboard_claims WHERE account_id = ? ORDER BY id")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    public boolean hasClaimedLeaderboard(String accountId, String tournamentId) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM tournament_leaderboard_claims WHERE account_id = ? AND tournament_id = ?")) {
            ps.setString(1, accountId);
            ps.setString(2, tournamentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Grants the leaderboard prize-table rewards for the account's rank, once. */
    public Claim claimLeaderboardReward(String accountId, String tournamentId, JsonNode definition,
                                        List<ObjectNode> standings) throws SQLException {
        JsonNode prizeTable = definition == null ? null : definition.path("leaderboard").path("prizeTable");
        Integer rank = null;
        for (ObjectNode s : standings) {
            if (s.path("accountID").asText().equals(accountId)) {
                rank = s.path("rank").asInt();
                break;
            }
        }
        if (rank == null) return new Claim(null, List.of(), "You did not participate in this tournament.");
        int finalRank = rank;
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM tournament_leaderboard_claims WHERE account_id = ? AND tournament_id = ?")) {
                ps.setString(1, accountId);
                ps.setString(2, tournamentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Claim(null, List.of(), "Leaderboard rewards already claimed.");
                }
            }
            List<JsonNode> granted = prizeRewardsFor(prizeTable, finalRank);
            if (!granted.isEmpty()) VersusRepository.grantRewards(c, accountId, granted);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tournament_leaderboard_claims (tournament_id, account_id, rank) VALUES (?,?,?)")) {
                ps.setString(1, tournamentId);
                ps.setString(2, accountId);
                ps.setInt(3, finalRank);
                ps.executeUpdate();
            }
            return new Claim(finalRank, granted, null);
        });
    }

    // ---- legacy live brackets: fees and prizes ---------------------------------------------------

    /** Charges every {currency, amount} row atomically. Returns error text or null. */
    public String chargeFees(String accountId, List<Fee> fees) throws SQLException {
        return tx(c -> {
            ensureWallet(c, accountId);
            for (Fee f : fees) {
                String field = CURRENCY_FIELDS.get(f.currency().toLowerCase(Locale.ROOT));
                if (field == null) return "Invalid entry fee currency.";
                if (f.amount() > 0 && readWalletColumn(c, accountId, field) < f.amount()) {
                    return "You cannot afford the entry fee.";
                }
            }
            for (Fee f : fees) {
                addToWalletColumn(c, accountId, CURRENCY_FIELDS.get(f.currency().toLowerCase(Locale.ROOT)), -f.amount());
            }
            return null;
        });
    }

    public void refundFees(String accountId, List<Fee> fees) throws SQLException {
        tx(c -> {
            ensureWallet(c, accountId);
            for (Fee f : fees) {
                String field = CURRENCY_FIELDS.get(f.currency().toLowerCase(Locale.ROOT));
                if (field != null) addToWalletColumn(c, accountId, field, f.amount());
            }
            return null;
        });
    }

    /** Grants rich reward dicts (versus shape): cards to the collection, Tokens to the wallet. */
    public void grantPrizeRewards(String accountId, List<JsonNode> rewards) throws SQLException {
        if (rewards == null || rewards.isEmpty()) return;
        tx(c -> {
            VersusRepository.grantRewards(c, accountId, rewards);
            return null;
        });
    }

    /** One entry-fee row of a live tournament (currency is lower-case). */
    public record Fee(String currency, long amount) {}

    private static void ensureWallet(Connection c, String accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO wallets (account_id, coins, gems, tickets) VALUES (?,?,?,?)")) {
            ps.setString(1, accountId);
            ps.setInt(2, PlayerRepository.STARTING_COINS);
            ps.setInt(3, PlayerRepository.STARTING_GEMS);
            ps.setInt(4, PlayerRepository.STARTING_TICKETS);
            ps.executeUpdate();
        }
    }

    private static long readWalletColumn(Connection c, String accountId, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM wallets WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Adds (or, when negative, subtracts) to a wallet column, clamped to 0 .. WALLET_MAX. */
    private static void addToWalletColumn(Connection c, String accountId, String column, long delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE wallets SET " + column + " = MAX(0, MIN("
                + PlayerRepository.WALLET_MAX + ", " + column + " + ?)) WHERE account_id = ?")) {
            ps.setLong(1, delta);
            ps.setString(2, accountId);
            ps.executeUpdate();
        }
    }
}
