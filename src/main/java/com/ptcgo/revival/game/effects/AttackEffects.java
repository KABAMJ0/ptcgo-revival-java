package com.ptcgo.revival.game.effects;

import java.util.List;

/**
 * Attack-effect factories that mirror the matching helpers in Python's {@code ptcgo/game/card_effects/attacks_common.py}
 * (the wording and order of operations follow the Python factory they are named after). Each returns an
 * {@link AttackEffect} for an {@link EffectRegistry.AttackSpec}. Damage goes through {@link EffectContext#dealDamage}
 * so Weakness/Resistance and registered damage modifiers apply exactly as they do for printed damage.
 */
public final class AttackEffects {
    private AttackEffects() {}

    /** Printed damage to the opposing Active, then the attacker damages itself (Python {@code recoil_attack}). */
    public static AttackEffect recoil(int selfDamage) {
        return ctx -> {
            ctx.dealDamage();
            ctx.dealDamage(selfDamage, ctx.source, false);
        };
    }

    /**
     * Flip {@code coins}; {@code perHeads} damage for each heads and no printed damage (Python
     * {@code flip_damage(coins=N, per_heads=M)}). Zero heads deals nothing.
     */
    public static AttackEffect flipPerHeads(int coins, int perHeads) {
        return ctx -> {
            int heads = countHeads(ctx.flipCoins(coins, ctx.title));
            int amount = heads * perHeads;
            if (amount > 0) ctx.dealDamage(amount, null, false);
        };
    }

    /**
     * Printed damage, plus {@code bonus} only when every one of the {@code coins} is heads (Python
     * {@code flip_damage(coins, bonus=N, require_all=True)}, i.e. {@code flip_bonus}). A tails run still deals the
     * printed damage because the bonus is present.
     */
    public static AttackEffect flipBonus(int bonus, int coins) {
        return ctx -> {
            List<Boolean> results = ctx.flipCoins(coins, ctx.title);
            boolean allHeads = !results.isEmpty() && results.stream().allMatch(Boolean::booleanValue);
            int base = ctx.printedDamage;
            int amount = allHeads ? base + bonus : (bonus != 0 ? base : 0);
            if (amount > 0) ctx.dealDamage(amount, null, false);
        };
    }

    /**
     * Printed damage, then the Defending Pokemon gets the Special Conditions (Python {@code condition_attack}). With
     * {@code flip} the conditions need {@code minHeads} heads out of {@code coins} flips, and the flip happens before
     * the damage; without it the conditions always apply.
     */
    public static AttackEffect condition(boolean flip, int coins, int minHeads, String... conditions) {
        return ctx -> {
            boolean success = true;
            if (flip) {
                success = countHeads(ctx.flipCoins(coins, ctx.title)) >= minHeads;
            }
            ctx.dealDamage();
            if (success) {
                for (String condition : conditions) ctx.applySpecialCondition(ctx.defender(), condition);
            }
        };
    }

    /** Printed damage, then heal the attacker for the damage it actually dealt (Python {@code heal_dealt_attack}). */
    public static AttackEffect healDealt() {
        return ctx -> {
            int dealt = ctx.dealDamage();
            if (dealt > 0 && ctx.source != null) ctx.heal(dealt, ctx.source);
        };
    }

    private static int countHeads(List<Boolean> results) {
        int heads = 0;
        for (boolean r : results) if (r) heads++;
        return heads;
    }
}
