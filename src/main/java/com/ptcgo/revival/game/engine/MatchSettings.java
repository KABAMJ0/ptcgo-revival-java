package com.ptcgo.revival.game.engine;

/**
 * Pacing, timers and matchmaking knobs. Defaults mirror the Python server; {@link #fromEnv()} reads the same
 * PTCGO_* variables, {@link #fast()} turns every animation pause off for tests.
 */
public final class MatchSettings {
    /** Sleep (ms) that paces client animations; 0 disables. */
    public boolean choreographyPauses = true;
    /** Pause after each AI action so the client can finish the animation (PTCGO_AI_ACTION_DELAY, seconds). */
    public long aiActionDelayMs = 1200;
    /** Seconds a lone queued player waits before the bot is paired (PTCGO_BOT_QUEUE_DELAY; 0 = wait for a human). */
    public double botQueueDelaySeconds = 6;
    public long readyCheckDelayMs = 1500;
    public long botReadyCheckDelayMs = 200;
    /** Confirm readiness on the client's behalf (the real client has no ConfirmReadyForMatch listener in play). */
    public boolean autoConfirmReady = true;
    public int actionTimeoutMs = com.ptcgo.revival.game.wire.GameConstants.ACTION_TIMEOUT_MS;
    public int followUpTimeoutMs = com.ptcgo.revival.game.wire.GameConstants.FOLLOW_UP_TIMEOUT_MS;
    public double reconnectGraceSeconds = com.ptcgo.revival.game.wire.GameConstants.RECONNECT_GRACE_SECONDS;
    public long shuffleAnimMs = 500;
    public long coinAnimMs = 3000;
    /** PTCGO_AI_POLICY: smart (default) | lite | basic. */
    public String aiPolicy = "smart";

    public static MatchSettings fromEnv() {
        MatchSettings s = new MatchSettings();
        String delay = System.getenv("PTCGO_AI_ACTION_DELAY");
        if (delay != null && !delay.isBlank()) {
            try {
                s.aiActionDelayMs = Math.max(0, Math.round(Double.parseDouble(delay.strip()) * 1000));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        String bot = System.getenv("PTCGO_BOT_QUEUE_DELAY");
        if (bot != null && !bot.isBlank()) {
            try {
                s.botQueueDelaySeconds = Double.parseDouble(bot.strip());
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        String policy = System.getenv("PTCGO_AI_POLICY");
        if (policy != null && !policy.isBlank()) s.aiPolicy = policy.strip();
        return s;
    }

    /** No animation pauses, short timers and a fast ready check: for tests. */
    public static MatchSettings fast() {
        MatchSettings s = new MatchSettings();
        s.choreographyPauses = false;
        s.aiActionDelayMs = 0;
        s.botQueueDelaySeconds = 0.3;
        s.readyCheckDelayMs = 20;
        s.botReadyCheckDelayMs = 10;
        s.shuffleAnimMs = 0;
        s.coinAnimMs = 0;
        return s;
    }
}
