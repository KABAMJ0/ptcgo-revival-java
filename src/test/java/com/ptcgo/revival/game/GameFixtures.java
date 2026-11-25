package com.ptcgo.revival.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.engine.GamePlayer;
import com.ptcgo.revival.game.engine.GameSession;
import com.ptcgo.revival.game.engine.MatchHooks;
import com.ptcgo.revival.game.engine.MatchSettings;

import java.util.List;
import java.util.Random;

/** Builds headless sessions. */
public final class GameFixtures {
    public static final String A = "00000000-0000-0000-0000-00000000000a";
    public static final String B = "00000000-0000-0000-0000-00000000000b";
    public static final String GAME = "11111111-1111-1111-1111-111111111111";

    private GameFixtures() {}

    public static GameSession aiVsAi(long seed, EffectRegistry registry, RecordingPlayer a, RecordingPlayer b) {
        return new GameSession(GAME, true, "Test", List.<GamePlayer>of(a, b), TestCatalog.catalog(), registry,
                MatchSettings.fast(), MatchHooks.NONE, new Random(seed), id -> {});
    }

    public static RecordingPlayer player(String id, String name, JsonNode deck) {
        return new RecordingPlayer(id, name, deck);
    }

    public static Catalog catalog() {
        return TestCatalog.catalog();
    }

    /** Starts the session and waits for GameCompleted (or fails after the timeout). */
    public static void runToCompletion(GameSession s, long timeoutMs) throws Exception {
        s.start();
        long end = System.currentTimeMillis() + timeoutMs;
        while (!s.isOver() && System.currentTimeMillis() < end) Thread.sleep(10);
        if (!s.isOver()) throw new AssertionError("match did not finish in " + timeoutMs + "ms, phase=" + s.phase());
    }
}
