package com.ptcgo.revival.game.effects;

import com.ptcgo.revival.game.engine.LegalActions;
import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.wire.Attr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Board counters and conditions used by count-based attacks. Each mirrors the Python helper of the same name in
 * {@code ptcgo/game/card_effects/attacks_common.py} (and {@code pokemon.py} for the energy and name tests).
 */
public final class Counters {
    private Counters() {}

    /** A count taken from the board when the attack resolves (Python {@code count_fn(ctx) -> int}). */
    @FunctionalInterface
    public interface Counter {
        int count(EffectContext ctx);
    }

    /** A yes/no test on the board when the attack resolves (Python {@code cond_fn(ctx) -> bool}). */
    @FunctionalInterface
    public interface Condition {
        boolean test(EffectContext ctx);
    }

    /** Python {@code _ENERGY_SCOPES}. */
    private static final Set<String> ENERGY_SCOPES =
            Set.of("self", "attacker", "defender", "opponent_active", "my_active", "mine", "opponent", "both");

    /**
     * Attached Energy on a scope (Python {@code count_energy(scope, energy_type, cards)}). Counts PROVIDED amounts
     * (Double Turbo = 2) unless {@code cards}; with {@code energyType} only energies that can provide that type count,
     * each counting the most of that type one of its options provides (at least 1).
     */
    public static Counter countEnergy(String scope, Integer energyType, boolean cards) {
        if (!ENERGY_SCOPES.contains(scope)) throw new IllegalArgumentException("count_energy: unknown scope '" + scope + "'");
        return ctx -> {
            List<CardEntity> pool = new ArrayList<>();
            switch (scope) {
                case "self", "attacker" -> pool.add(ctx.source);
                case "defender", "opponent_active" -> pool.add(ctx.defender());
                case "my_active" -> pool.add(ctx.myActive());
                default -> pool.addAll(ctx.pokemonInPlay(scope));
            }
            int total = 0;
            for (CardEntity pokemon : pool) {
                if (pokemon == null) continue;
                for (CardEntity energy : Board.attachedEnergies(pokemon)) {
                    if (energyType == null) {
                        total += cards ? 1 : LegalActions.energyProvidedCount(energy);
                    } else if (energyProvidesType(energy, energyType)) {
                        total += cards ? 1 : providedOfType(energy, energyType);
                    }
                }
            }
            return total;
        };
    }

    public static Counter countEnergy(String scope) {
        return countEnergy(scope, null, false);
    }

    /** Whether one energy can provide {@code type} (Python {@code energy_provides_type}: options, else printed types). */
    public static boolean energyProvidesType(CardEntity card, int type) {
        if (card == null || !card.isEnergy()) return false;
        for (List<Integer> option : card.energyOptions()) if (option.contains(type)) return true;
        return card.intList(Attr.POKEMON_TYPES).contains(type);
    }

    /** Python {@code _provided_of_type}: the most of {@code type} one option provides, or 1. */
    static int providedOfType(CardEntity energy, int type) {
        int best = 0;
        for (List<Integer> option : energy.energyOptions()) {
            int n = 0;
            for (int t : option) if (t == type) n++;
            best = Math.max(best, n);
        }
        return best == 0 ? 1 : best;
    }

    /** Damage counters on the attacker ("self") or, for anything else, the Defending Pokemon (Python {@code damage_counters_on}). */
    public static Counter damageCountersOn(String target) {
        return ctx -> {
            CardEntity t = "self".equals(target) ? ctx.source : ctx.defender();
            if (t == null) return 0;
            return Math.max(0, (ctx.ops.maxHp(t) - t.hp()) / 10);
        };
    }

    /** Benched Pokemon on a side, optionally filtered (Python {@code count_bench(side, pred)}). */
    public static Counter countBench(String side, Predicate<CardEntity> pred) {
        return ctx -> {
            int n = 0;
            for (CardEntity p : ctx.bench(side)) if (pred == null || pred.test(p)) n++;
            return n;
        };
    }

    public static Counter countBench(String side) {
        return countBench(side, null);
    }

    /** Python {@code count_prizes_taken(side)}. */
    public static Counter countPrizesTaken(String side) {
        return ctx -> ctx.prizesTaken(side);
    }

    /** Python {@code count_hand(side)}. */
    public static Counter countHand(String side) {
        return ctx -> ctx.handSize(side);
    }

    /** The Defending Pokemon's printed retreat cost (Drednaw's {@code _defender_retreat_cost}). */
    public static Counter defenderPrintedRetreatCost() {
        return ctx -> {
            CardEntity d = ctx.defender();
            return d == null ? 0 : d.getInt(Attr.RETREAT_COST, 0);
        };
    }

    /** Energy provided to both Active Pokemon (Victini V's {@code _both_actives_energy}). */
    public static Counter bothActivesEnergy() {
        return ctx -> {
            int total = 0;
            for (CardEntity p : new CardEntity[] {ctx.myActive(), ctx.defender()}) {
                if (p == null) continue;
                for (CardEntity e : Board.attachedEnergies(p)) total += LegalActions.energyProvidedCount(e);
            }
            return total;
        };
    }

    // ---- conditions ------------------------------------------------------------------------------------------------

    /** Python {@code has_damage(target)}: "self" is the attacker, anything else the Defending Pokemon. */
    public static Condition hasDamage(String target) {
        return ctx -> {
            CardEntity t = "self".equals(target) ? ctx.source : ctx.defender();
            return t != null && t.hp() < ctx.ops.maxHp(t);
        };
    }

    /** Python {@code defender_has_condition(condition)} (client condition name, e.g. "Asleep"). */
    public static Condition defenderHasCondition(String condition) {
        return ctx -> {
            CardEntity d = ctx.defender();
            return d != null && d.conditions().contains(condition);
        };
    }

    /** Python {@code defender_is_v}: any Pokemon V (V, VSTAR, VMAX, V-UNION). */
    public static Condition defenderIsV() {
        return ctx -> {
            CardEntity d = ctx.defender();
            return d != null && (d.hasSubtype("V") || d.hasSubtype("VSTAR") || d.hasSubtype("VMAX") || d.hasSubtype("V-UNION"));
        };
    }

    /** Python {@code defender_is_gx}. */
    public static Condition defenderIsGx() {
        return ctx -> {
            CardEntity d = ctx.defender();
            return d != null && d.hasSubtype("GX");
        };
    }

    /** Python {@code named_in_play(*names, side)}: any of the names is in play on the side (name_key comparison). */
    public static Condition namedInPlay(String side, String... names) {
        return ctx -> {
            Set<String> present = new java.util.HashSet<>();
            for (CardEntity p : ctx.pokemonInPlay(side)) present.add(nameKey(p.getString(Attr.EVOLUTION_LOGIC_NAME)));
            for (String n : names) if (present.contains(nameKey(n))) return true;
            return false;
        };
    }

    private static final Pattern NAME_SEPARATORS = Pattern.compile("[\\s\\-_.’']+");

    /** Python {@code name_key}: one key for a card name however it is spelled ("Regirock ex" = "Regirockex"). */
    public static String nameKey(String text) {
        String folded = text == null ? "" : text;
        folded = folded.replace("★", "star").replace("◇", "prism");
        folded = folded.replace("é", "e").replace("É", "E");
        // encode("ascii", "ignore"): drop every non-ASCII character
        StringBuilder ascii = new StringBuilder();
        for (char ch : folded.toCharArray()) if (ch < 128) ascii.append(ch);
        return NAME_SEPARATORS.matcher(ascii.toString().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** A Pokemon predicate on its logic name (e.g. Bisharp's {@code _is_pawniard}: EVOLUTION_LOGIC_NAME == "Pawniard"). */
    public static Predicate<CardEntity> logicNameIs(String name) {
        return p -> name.equals(p.getString(Attr.EVOLUTION_LOGIC_NAME));
    }
}
