package com.ptcgo.revival.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.progression.Quests;
import com.ptcgo.revival.progression.Quests.Template;
import com.ptcgo.revival.progression.Uuid5;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Daily challenges: transactional offers, selection, abandonment and exactly-once match credit. */
public final class QuestRepository {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database db;
    private final List<Template> catalog;
    private final Supplier<Instant> clock;

    public QuestRepository(Database db, List<Template> catalog, Supplier<Instant> clock) {
        this.db = db;
        this.catalog = catalog;
        this.clock = clock;
    }

    public QuestRepository(Database db) {
        this(db, Quests.loadCatalog(), Instant::now);
    }

    public record Snapshot(ObjectNode quests, ObjectNode configuration) {}

    public record AccountState(boolean enabled, Map<String, Integer> affinityXp) {}

    public record MatchCredit(ArrayNode completedQuestsAndXpTotal, ArrayNode progressedQuests, ArrayNode rewards) {}

    private static final class Row {
        String questId;
        LocalDate offeredDate;
        String status;
        Template template;
        int activations;
        Double acceptedAt;
    }

    private static final class State {
        boolean enabled = true;
        LocalDate selectedDate;
        Map<String, Integer> xp = new LinkedHashMap<>();
    }

    // ---- public operations -----------------------------------------------------------------------

    /** Quest attributes for the account object: enabled flag and per-affinity xp (defaults when no row yet). */
    public AccountState accountState(String accountId) throws SQLException {
        try (Connection c = db.connect()) {
            State s = readState(c, accountId);
            return s == null ? new AccountState(true, Map.of()) : new AccountState(s.enabled, s.xp);
        }
    }

    public Snapshot get(String accountId) throws SQLException {
        return tx(accountId, (c, s, now) -> snapshot(c, accountId, s, now));
    }

    public Snapshot setEnabled(String accountId, boolean enabled) throws SQLException {
        return tx(accountId, (c, s, now) -> {
            s.enabled = enabled;
            writeState(c, accountId, s);
            return snapshot(c, accountId, s, now);
        });
    }

    /** Accepts exactly one of today's offered quests; anything else just returns the current snapshot. */
    public Snapshot select(String accountId, List<String> questIds) throws SQLException {
        return tx(accountId, (c, s, now) -> {
            Snapshot snap = snapshot(c, accountId, s, now);
            if (questIds.size() != 1 || !offered(snap).contains(questIds.get(0))) return snap;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE daily_quests SET status = 'active', accepted_at = ? WHERE quest_id = ?")) {
                ps.setDouble(1, now.toEpochMilli() / 1000.0);
                ps.setString(2, questIds.get(0));
                ps.executeUpdate();
            }
            s.selectedDate = today(now);
            writeState(c, accountId, s);
            return snapshot(c, accountId, s, now);
        });
    }

    public Snapshot abandon(String accountId, List<String> questIds) throws SQLException {
        return tx(accountId, (c, s, now) -> {
            for (Row r : rows(c, accountId)) {
                if (r.status.equals("active") && questIds.contains(r.questId)) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE daily_quests SET status = 'abandoned' WHERE quest_id = ?")) {
                        ps.setString(1, r.questId);
                        ps.executeUpdate();
                    }
                }
            }
            return snapshot(c, accountId, s, now);
        });
    }

    /**
     * Applies server-owned match counters to quests accepted before the match started and pays completed rewards.
     * Each (account, game) is credited once. {@code stats} keys: damagedealt, energyplayed, trainersplayed,
     * prizecardstaken ("wins" is derived from {@code won}).
     */
    public MatchCredit creditMatch(String accountId, String gameId, Map<String, Integer> stats, boolean won,
                                   double startedAtEpochSeconds) throws SQLException {
        return tx(accountId, (c, s, now) -> {
            ArrayNode completed = JSON.createArrayNode(), progressed = JSON.createArrayNode(), rewards = JSON.createArrayNode();
            MatchCredit result = new MatchCredit(completed, progressed, rewards);
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO quest_match_credits (account_id, game_id) VALUES (?,?)")) {
                ps.setString(1, accountId);
                ps.setString(2, gameId);
                if (ps.executeUpdate() == 0) return result; // already credited
            }
            if (!s.enabled) return result;
            Map<String, Integer> xp = new LinkedHashMap<>(s.xp);
            Map<String, Integer> counters = new HashMap<>(stats);
            counters.put("wins", won ? 1 : 0);
            int maxXp = Quests.XP_LEVELS.values().stream().max(Integer::compare).orElse(0);
            for (Row r : rows(c, accountId)) {
                if (!r.status.equals("active") || r.acceptedAt == null || r.acceptedAt > startedAtEpochSeconds) continue;
                int delta = Math.max(0, counters.getOrDefault(r.template.stat(), 0));
                if (delta == 0) continue;
                r.activations = Math.min(r.template.target(), r.activations + delta);
                ObjectNode payload = Quests.questPayload(r.questId, r.template, r.activations);
                if (r.activations < r.template.target()) {
                    setProgress(c, r.questId, r.activations, "active");
                    progressed.add(payload);
                    continue;
                }
                setProgress(c, r.questId, r.activations, "completed");
                int oldXp = xp.getOrDefault("Colorless", 0);
                int newXp = Math.min(maxXp, oldXp + r.template.xp());
                xp.put("Colorless", newXp);
                // List<List<QuestDefinition>>: a bare int in the pair fails client deserialization, so the xp total
                // rides as the pair's second definition.
                ObjectNode def = (ObjectNode) payload.get("questDefinition");
                ArrayNode pair = completed.addArray();
                pair.add(def);
                ObjectNode withXp = def.deepCopy();
                withXp.put("xp", newXp);
                pair.add(withXp);
                def.get("rewards").forEach(rewards::add);
                for (Map.Entry<Integer, Integer> level : Quests.XP_LEVELS.entrySet()) {
                    if (oldXp < level.getValue() && level.getValue() <= newXp) {
                        rewards.add(Quests.coinReward(25, "ColorlessLevel" + level.getKey()));
                    }
                }
            }
            s.xp = xp;
            writeState(c, accountId, s);
            int coins = 0;
            for (JsonNode r : rewards) coins += r.path("rewardAmount").asInt();
            if (coins > 0) VersusRepository.addToWallet(c, accountId, "coins", coins);
            return result;
        });
    }

    // ---- transaction plumbing --------------------------------------------------------------------

    private interface Work<T> {
        T run(Connection c, State state, Instant now) throws SQLException;
    }

    private <T> T tx(String accountId, Work<T> work) throws SQLException {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                State s = readState(c, accountId);
                if (s == null) {
                    if (!accountExists(c, accountId)) throw new SQLException("Unknown account " + accountId);
                    s = new State();
                    writeState(c, accountId, s);
                }
                T result = work.run(c, s, clock.get());
                c.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    private static boolean accountExists(Connection c, String accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM accounts WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static State readState(Connection c, String accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT enabled, selected_date, affinity_xp FROM quest_accounts WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                State s = new State();
                s.enabled = rs.getBoolean(1);
                String d = rs.getString(2);
                s.selectedDate = d == null ? null : LocalDate.parse(d);
                try {
                    s.xp = new LinkedHashMap<>(JSON.readValue(rs.getString(3), new TypeReference<Map<String, Integer>>() {}));
                } catch (Exception e) {
                    s.xp = new LinkedHashMap<>();
                }
                return s;
            }
        }
    }

    private static void writeState(Connection c, String accountId, State s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO quest_accounts (account_id, enabled, selected_date, affinity_xp) VALUES (?,?,?,?) "
                        + "ON CONFLICT(account_id) DO UPDATE SET enabled=excluded.enabled, selected_date=excluded.selected_date, "
                        + "affinity_xp=excluded.affinity_xp")) {
            ps.setString(1, accountId);
            ps.setBoolean(2, s.enabled);
            ps.setString(3, s.selectedDate == null ? null : s.selectedDate.toString());
            try {
                ps.setString(4, JSON.writeValueAsString(s.xp));
            } catch (Exception e) {
                throw new SQLException(e);
            }
            ps.executeUpdate();
        }
    }

    private List<Row> rows(Connection c, String accountId) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT quest_id, offered_date, status, definition, activations, accepted_at FROM daily_quests WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Row r = new Row();
                    r.questId = rs.getString(1);
                    r.offeredDate = LocalDate.parse(rs.getString(2));
                    r.status = rs.getString(3);
                    try {
                        r.template = Template.fromJson(JSON.readTree(rs.getString(4)));
                    } catch (Exception e) {
                        throw new SQLException("Unreadable quest definition " + r.questId, e);
                    }
                    r.activations = rs.getInt(5);
                    double at = rs.getDouble(6);
                    r.acceptedAt = rs.wasNull() ? null : at;
                    out.add(r);
                }
            }
        }
        return out;
    }

    private static void setProgress(Connection c, String questId, int activations, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE daily_quests SET activations = ?, status = ? WHERE quest_id = ?")) {
            ps.setInt(1, activations);
            ps.setString(2, status);
            ps.setString(3, questId);
            ps.executeUpdate();
        }
    }

    private static LocalDate today(Instant now) {
        return now.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    private static Set<String> offered(Snapshot snap) {
        Set<String> ids = new java.util.HashSet<>();
        snap.quests().path("availableQuests").forEach(q -> ids.add(q.path("questDefinition").path("questID").asText()));
        return ids;
    }

    /** Expires stale offers, offers two new templates once per UTC day, and builds the AllQuests snapshot. */
    private Snapshot snapshot(Connection c, String accountId, State state, Instant now) throws SQLException {
        LocalDate today = today(now);
        List<Row> rows = rows(c, accountId);
        List<Row> active = new ArrayList<>();
        for (Row r : rows) if (r.status.equals("active")) active.add(r);
        for (Row r : rows) {
            if (r.status.equals("available") && !r.offeredDate.equals(today)) {
                r.status = "expired";
                try (PreparedStatement ps = c.prepareStatement("UPDATE daily_quests SET status = 'expired' WHERE quest_id = ?")) {
                    ps.setString(1, r.questId);
                    ps.executeUpdate();
                }
            }
        }
        List<Row> available = new ArrayList<>();
        if (state.enabled && !today.equals(state.selectedDate) && active.size() < Quests.MAX_ACTIVE) {
            for (Row r : rows) if (r.status.equals("available")) available.add(r);
            if (available.isEmpty()) {
                Set<String> activeKeys = new java.util.HashSet<>();
                active.forEach(r -> activeKeys.add(r.template.key()));
                List<Template> candidates = new ArrayList<>();
                for (Template t : catalog) if (!activeKeys.contains(t.key())) candidates.add(t);
                candidates.sort(Comparator.comparing(t -> sha256(accountId + ":" + today + ":" + t.key()), Arrays::compareUnsigned));
                for (Template t : candidates.subList(0, Math.min(2, candidates.size()))) {
                    Row r = new Row();
                    r.questId = Uuid5.of(Uuid5.NAMESPACE_URL, "spirit:daily:" + accountId + ":" + today + ":" + t.key()).toString();
                    r.offeredDate = today;
                    r.status = "available";
                    r.template = t;
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO daily_quests (quest_id, account_id, offered_date, status, definition, activations) VALUES (?,?,?,?,?,0)")) {
                        ps.setString(1, r.questId);
                        ps.setString(2, accountId);
                        ps.setString(3, today.toString());
                        ps.setString(4, "available");
                        ps.setString(5, t.toJson().toString());
                        ps.executeUpdate();
                    }
                    available.add(r);
                }
            }
        }
        ObjectNode quests = JSON.createObjectNode();
        ArrayNode current = quests.putArray("currentQuests");
        if (state.enabled) for (Row r : active) current.add(Quests.questPayload(r.questId, r.template, r.activations));
        ArrayNode avail = quests.putArray("availableQuests");
        for (Row r : available) avail.add(Quests.questPayload(r.questId, r.template, r.activations));
        quests.putArray("specialQuests");
        Instant tomorrow = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant next = available.isEmpty() ? tomorrow : now;
        return new Snapshot(quests, Quests.configuration(next.toEpochMilli()));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
