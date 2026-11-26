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

    // ---- count-based damage and conditional bonuses ------------------------------------------------------------------

    /**
     * {@code base + per * count} damage to the opposing Active, capped when {@code cap} is set, then the attacker damages
     * itself for {@code selfDamage} (Python {@code damage_per(count_fn, per, base, cap, self_damage)}). The printed
     * damage is not added: {@code base} replaces it, as in Python. Nothing is dealt when the total is 0 or less.
     */
    public static AttackEffect damagePer(Counters.Counter counter, int per, int base, Integer cap, int selfDamage) {
        return ctx -> {
            int amount = base + per * Math.max(0, counter.count(ctx));
            if (cap != null) amount = Math.min(amount, cap);
            if (amount > 0) ctx.dealDamage(amount, null, false);
            if (selfDamage > 0) ctx.dealDamage(selfDamage, ctx.source, false);
        };
    }

    public static AttackEffect damagePer(Counters.Counter counter, int per, int base) {
        return damagePer(counter, per, base, null, 0);
    }

    /**
     * Printed (or {@code base}) damage, plus {@code bonus} when the condition holds; with {@code elseNothing} the attack
     * does nothing when it fails (Python {@code bonus_if(cond_fn, bonus, base, else_nothing)}). The condition is
     * checked before any damage.
     */
    public static AttackEffect bonusIf(Counters.Condition condition, int bonus, Integer base, boolean elseNothing) {
        return ctx -> {
            int baseVal = base != null ? base : ctx.printedDamage;
            boolean met = condition.test(ctx);
            int amount = met ? baseVal + bonus : (elseNothing ? 0 : baseVal);
            if (amount > 0) ctx.dealDamage(amount, null, false);
        };
    }

    // ---- coin-flip variants ----------------------------------------------------------------------------------------

    /**
     * "Flip a coin until you get tails. This attack does {@code perHeads} damage for each heads" on top of {@code base}
     * (Python {@code flip_damage(until_tails=True, base, per_heads)}; with per_heads set a missing base is 0).
     */
    public static AttackEffect flipUntilTails(int base, int perHeads) {
        return ctx -> {
            int heads = ctx.flipUntilTails(ctx.title);
            int amount = base + heads * perHeads;
            if (amount > 0) ctx.dealDamage(amount, null, false);
        };
    }

    /**
     * Flip one coin per counted thing; {@code perHeads} damage for each heads (Python
     * {@code flip_damage(coins_from=count_fn, per_heads)}; no base, so zero heads or zero coins deals nothing).
     */
    public static AttackEffect flipCoinsFrom(Counters.Counter coins, int perHeads) {
        return ctx -> {
            int total = Math.max(0, coins.count(ctx));
            int heads = total > 0 ? countHeads(ctx.flipCoins(total, ctx.title)) : 0;
            int amount = heads * perHeads;
            if (amount > 0) ctx.dealDamage(amount, null, false);
        };
    }

    // ---- Special Conditions with the full option set ---------------------------------------------------------------

    /** The options of Python {@code condition_attack} that the simple {@link #condition} factory leaves out. */
    public record ConditionOptions(boolean flip, int coins, int minHeads, List<String> conditions,
                                   List<String> alwaysConditions, List<String> selfConditions, boolean noRetreat,
                                   int checkupCoins) {
        public static ConditionOptions of(String... conditions) {
            return new ConditionOptions(false, 1, 1, List.of(conditions), List.of(), List.of(), false, 1);
        }

        public ConditionOptions withFlip(int coins, int minHeads) {
            return new ConditionOptions(true, coins, minHeads, conditions, alwaysConditions, selfConditions, noRetreat, checkupCoins);
        }

        public ConditionOptions withAlways(String... always) {
            return new ConditionOptions(flip, coins, minHeads, conditions, List.of(always), selfConditions, noRetreat, checkupCoins);
        }

        public ConditionOptions withSelf(String... self) {
            return new ConditionOptions(flip, coins, minHeads, conditions, alwaysConditions, List.of(self), noRetreat, checkupCoins);
        }

        public ConditionOptions withNoRetreat() {
            return new ConditionOptions(flip, coins, minHeads, conditions, alwaysConditions, selfConditions, true, checkupCoins);
        }

        public ConditionOptions withCheckupCoins(int n) {
            return new ConditionOptions(flip, coins, minHeads, conditions, alwaysConditions, selfConditions, noRetreat, n);
        }
    }

    /**
     * Python {@code condition_attack} in the order it runs: the flip (if any), printed damage, then
     * {@code always + (conditions on success)} on the Defending Pokemon, then on success the attacker's
     * {@code self_conditions} and the "can't retreat" lock on the Defending Pokemon.
     */
    public static AttackEffect conditionAttack(ConditionOptions o) {
        return ctx -> {
            boolean success = true;
            if (o.flip()) success = countHeads(ctx.flipCoins(o.coins(), ctx.title)) >= o.minHeads();
            ctx.dealDamage();
            List<String> applied = new java.util.ArrayList<>(o.alwaysConditions());
            if (success) applied.addAll(o.conditions());
            for (String c : applied) ctx.applySpecialCondition(ctx.defender(), c, o.checkupCoins());
            if (success) {
                for (String c : o.selfConditions()) ctx.applySpecialCondition(ctx.source, c, o.checkupCoins());
                if (o.noRetreat()) ctx.lockRetreat(ctx.defender());
            }
        };
    }

    // ---- card movement -----------------------------------------------------------------------------------------------

    /**
     * Printed damage, then discard {@code count} Energy (or all with {@code all}) from the attacker, optionally only
     * energies that provide {@code energyType} (Python {@code self_energy_discard_attack} with the default order).
     */
    public static AttackEffect selfEnergyDiscard(int count, boolean all, Integer energyType) {
        return ctx -> {
            ctx.dealDamage();
            java.util.function.Predicate<com.ptcgo.revival.game.model.CardEntity> pred =
                    energyType == null ? null : e -> Counters.energyProvidesType(e, energyType);
            ctx.discardEnergyFrom(ctx.source, all ? 99 : count, pred, null, "Choose Energy to discard from this Pokémon");
        };
    }

    /**
     * Printed damage, then discard {@code count} Energy (special Energy only with {@code specialOnly}) from the
     * opponent's Active (Python {@code discard_opponent_energy_attack} with the default {@code after_damage}).
     */
    public static AttackEffect discardOpponentEnergy(int count, boolean specialOnly) {
        return ctx -> {
            ctx.dealDamage();
            var target = ctx.defender();
            if (target == null) return;
            java.util.function.Predicate<com.ptcgo.revival.game.model.CardEntity> pred = specialOnly
                    ? e -> Boolean.TRUE.equals(e.get(com.ptcgo.revival.game.wire.Attr.IS_SPECIAL_ENERGY)) : null;
            ctx.discardEnergyFrom(target, count, pred, null, "Choose Energy to discard from the Defending Pokémon");
        };
    }

    /** Printed damage, then discard the top {@code count} cards of a deck (the opponent's by default; Python {@code mill_attack}). */
    public static AttackEffect mill(int count, boolean opponent) {
        return ctx -> {
            ctx.dealDamage();
            String owner = opponent ? ctx.opponentId() : ctx.playerId;
            var milled = ctx.deckTop(count, owner);
            if (!milled.isEmpty()) ctx.discardCards(milled);
        };
    }

    /** Printed damage, then draw {@code n} cards (Python {@code draw_attack}). */
    public static AttackEffect drawAttack(int n) {
        return ctx -> {
            ctx.dealDamage();
            ctx.draw(n);
        };
    }
}
