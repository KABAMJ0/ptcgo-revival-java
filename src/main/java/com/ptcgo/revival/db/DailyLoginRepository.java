package com.ptcgo.revival.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.progression.DailyRewards;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Advances the daily-login streak and grants today's reward once per UTC day (ptcgo/database/daily_rewards.py). */
public final class DailyLoginRepository {
    private final Database db;
    private final DailyRewards rewards;
    private final Catalog catalog;
    private final Supplier<Instant> clock;
    private final RandomGenerator rng;

    public DailyLoginRepository(Database db, DailyRewards rewards, Catalog catalog, Supplier<Instant> clock, RandomGenerator rng) {
        this.db = db;
        this.rewards = rewards;
        this.catalog = catalog;
        this.clock = clock;
        this.rng = rng;
    }

    public DailyRewards rewards() {
        return rewards;
    }

    public record Info(boolean firstDailyLogin, int activations, int rewardDay, long nextRewardTimestampMs, List<JsonNode> granted) {}

    public Info process(String accountId) throws SQLException {
        LocalDate today = clock.get().atOffset(ZoneOffset.UTC).toLocalDate();
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            try {
                LocalDate last = null;
                int streak = 0, activations = 0;
                boolean exists = false;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT last_claim_date, streak, activations FROM daily_login_progress WHERE account_id = ?")) {
                    ps.setString(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            exists = true;
                            String d = rs.getString(1);
                            last = d == null ? null : LocalDate.parse(d);
                            streak = rs.getInt(2);
                            activations = rs.getInt(3);
                        }
                    }
                }
                boolean first = !today.equals(last);
                List<JsonNode> granted = List.of();
                if (first) {
                    boolean broke = last == null || java.time.temporal.ChronoUnit.DAYS.between(last, today) > 1;
                    streak = broke ? 1 : streak + 1;
                    activations += 1;
                    last = today;
                    granted = rewards.rewardsFor(streak, activations);
                    grant(c, accountId, granted);
                }
                try (PreparedStatement up = c.prepareStatement(
                        "INSERT INTO daily_login_progress (account_id, last_claim_date, streak, activations) VALUES (?,?,?,?) "
                                + "ON CONFLICT(account_id) DO UPDATE SET last_claim_date=excluded.last_claim_date, "
                                + "streak=excluded.streak, activations=excluded.activations")) {
                    up.setString(1, accountId);
                    up.setString(2, last == null ? null : last.toString());
                    up.setInt(3, streak);
                    up.setInt(4, activations);
                    up.executeUpdate();
                }
                c.commit();
                long nextMidnight = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
                return new Info(first, activations, rewards.rewardDay(streak, activations), nextMidnight, granted);
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    /** Maps daily rewards onto grants: Tokens/TournamentTicket to the wallet, boosters and cards to the collection (non-tradable). */
    private void grant(Connection c, String accountId, List<JsonNode> list) throws SQLException {
        Map<String, Integer> products = new LinkedHashMap<>();
        long coins = 0, tickets = 0;
        for (JsonNode r : list) {
            int amount = Math.max(r.path("rewardAmount").asInt(0), 0);
            switch (r.path("rewardType").asText()) {
                case "Tokens" -> coins += amount;
                case "TournamentTicket" -> tickets += amount;
                case "RandomBooster" -> {
                    String guid = randomPackGuid();
                    if (guid != null) products.merge(guid, Math.max(amount, 1), Integer::sum);
                }
                case "Archetype" -> {
                    String guid = r.path("rewardProductID").asText("");
                    if (!guid.isEmpty()) products.merge(guid, Math.max(amount, 1), Integer::sum);
                }
                default -> { }
            }
        }
        for (Map.Entry<String, Integer> e : products.entrySet()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO collection (account_id, archetype_id, tradable_count, nontradable_count) VALUES (?,?,0,?) "
                            + "ON CONFLICT(account_id, archetype_id) DO UPDATE SET nontradable_count = nontradable_count + excluded.nontradable_count")) {
                ps.setString(1, accountId);
                ps.setString(2, e.getKey().toLowerCase(Locale.ROOT));
                ps.setInt(3, e.getValue());
                ps.executeUpdate();
            }
        }
        if (coins > 0) VersusRepository.addToWallet(c, accountId, "coins", (int) Math.min(coins, PlayerRepository.WALLET_MAX));
        if (tickets > 0) VersusRepository.addToWallet(c, accountId, "tickets", (int) Math.min(tickets, PlayerRepository.WALLET_MAX));
    }

    private String randomPackGuid() {
        List<String> packs = new ArrayList<>();
        for (Catalog.Product p : catalog.products()) if (p.kind().equals("booster_pack")) packs.add(p.guid());
        return packs.isEmpty() ? null : packs.get(rng.nextInt(packs.size()));
    }
}
