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

    /**
     * Printed damage, then heal {@code amount} from the attacker itself (Python {@code heal_attack(amount)} with the
     * default {@code target='self'}, no discard cost and no {@code all_damage}). Heals only what is missing.
     */
    public static AttackEffect healSelf(int amount) {
        return ctx -> {
            ctx.dealDamage();
            if (amount > 0) ctx.heal(amount, ctx.source);
        };
    }

    /**
     * Flip {@code coins}; any tails and the attack does nothing, all heads deals the printed damage (Python
     * {@code flip_or_nothing(coins)} with the default {@code then}). Zero coins also does nothing.
     */
    public static AttackEffect flipOrNothing(int coins) {
        return ctx -> {
            List<Boolean> results = ctx.flipCoins(coins, ctx.title);
            if (results.isEmpty() || !results.stream().allMatch(Boolean::booleanValue)) return;
            ctx.dealDamage();
        };
    }

    /**
     * Printed damage, plus {@code bonus} when the Defending Pokemon already has every named Special Condition (Python
     * {@code condition_bonus_attack(bonus, *conditions)}). The check happens before the hit and applies no condition.
     * With no conditions named the bonus never applies.
     */
    public static AttackEffect conditionBonus(int bonus, String... conditions) {
        return ctx -> {
            var defender = ctx.defender();
            List<String> current = defender == null ? List.of() : defender.conditions();
            boolean met = conditions.length > 0 && java.util.Arrays.stream(conditions).allMatch(current::contains);
            int amount = ctx.printedDamage + (met ? bonus : 0);
            if (amount > 0) ctx.dealDamage(amount, null, false);
        };
    }

    /**
     * Flip {@code coins}: {@code perHeads} damage for each heads, and the Defending Pokemon gets {@code condition} when at
     * least {@code minHeads} came up heads (Python {@code flip_damage_condition} with its default {@code base}). With
     * {@code perHeads} set there is no printed damage; with {@code perHeads} zero the printed damage is dealt on every
     * outcome. A {@code null} condition only changes the damage. Damage happens before the condition, as in Python.
     */
    public static AttackEffect flipDamageCondition(int coins, int perHeads, int minHeads, String condition) {
        return ctx -> {
            List<Boolean> results = coins > 0 ? ctx.flipCoins(coins, ctx.title) : List.of();
            int heads = countHeads(results);
            int base = perHeads != 0 ? 0 : ctx.printedDamage;
            int amount = base + heads * perHeads;
            if (amount > 0) ctx.dealDamage(amount, null, false);
            if (heads >= minHeads && condition != null) ctx.applySpecialCondition(ctx.defender(), condition);
        };
    }

    private static int countHeads(List<Boolean> results) {
        int heads = 0;
        for (boolean r : results) if (r) heads++;
        return heads;
    }
}
