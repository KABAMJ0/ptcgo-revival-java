package com.ptcgo.revival.game.effects;

import java.util.HashMap;
import java.util.Map;

/** Per-entity Special Condition bookkeeping that is not part of the wire attribute (sleep/poison/confusion/paralysis). */
public final class ConditionState {
    /** entity id -> coins flipped at checkup while Asleep (waking needs ALL heads). */
    public final Map<String, Integer> sleepCheckupCoins = new HashMap<>();
    /** entity id -> damage-counter multiplier while Poisoned (10 x count each checkup; default 1). */
    public final Map<String, Integer> poisonCounters = new HashMap<>();
    /** entity id -> Confusion self-hit damage when not the default 30. */
    public final Map<String, Integer> confusionDamage = new HashMap<>();
    /** entity id -> turn number Paralyzed was applied (cures after the checkup following the owner's next turn). */
    public final Map<String, Integer> paralyzedSince = new HashMap<>();

    public void clear(String entityId) {
        sleepCheckupCoins.remove(entityId);
        poisonCounters.remove(entityId);
        confusionDamage.remove(entityId);
        paralyzedSince.remove(entityId);
    }
}
