package com.ptcgo.revival.progression;

import com.ptcgo.revival.db.DailyLoginRepository;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.db.QuestRepository;
import com.ptcgo.revival.db.VersusRepository;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Quests, versus ladder and seasons, bundled for {@code Services}. */
public record Progression(QuestRepository quests, VersusRepository versus, Seasons seasons, DailyLoginRepository daily) {
    /** Client setting numbers inside the account-settings dict (attribute 10230). */
    public static final int LAST_SEEN_POINTS_SETTING = 109; // LastKnownSeasonPointTotal: the ladder animation anchor
    public static final int[] SEEN_SEASON_SETTINGS = {111, 112, 113, 114}; // start hi/lo, end hi/lo

    /**
     * LOGIN-ONLY, before attributes are built: set the client's LastKnownSeasonPointTotal to the current season
     * points so a relog never replays the ladder's points/reward animation.
     */
    public int anchorVersusAnimation(PlayerRepository players, String accountId) throws SQLException {
        int points = versus.progress(accountId, seasons.activeId()).points();
        players.mergeAccountSettings(accountId, Map.of(String.valueOf(LAST_SEEN_POINTS_SETTING), points));
        return points;
    }

    /** LOGIN-ONLY, after attributes are built: store the season the client's new-season dialog compares against. */
    public void acknowledgeSeason(PlayerRepository players, com.ptcgo.revival.catalog.Catalog catalog, String accountId)
            throws SQLException {
        var payload = seasons.clientPayload(catalog);
        long start = payload.path("startTime").asLong(), end = payload.path("endTime").asLong();
        long[] longs = {start >> 32, (int) start, end >> 32, (int) end};
        Map<String, Object> seen = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) seen.put(String.valueOf(SEEN_SEASON_SETTINGS[i]), (int) longs[i]);
        Map<String, Object> stored = players.accountSettings(accountId);
        boolean differs = seen.entrySet().stream()
                .anyMatch(e -> !String.valueOf(e.getValue()).equals(String.valueOf(stored.get(e.getKey()))));
        if (differs) players.mergeAccountSettings(accountId, seen);
    }
}
