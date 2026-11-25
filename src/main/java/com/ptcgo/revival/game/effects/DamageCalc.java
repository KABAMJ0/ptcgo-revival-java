package com.ptcgo.revival.game.effects;

import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.wire.Attr;

import java.util.ArrayList;
import java.util.List;

/**
 * One damage computation (passives.py DamageCalc + compute_damage): dealt-modifiers, weakness/resistance, taken
 * modifiers, prevention. Weakness doubles; resistance subtracts the defender's printed amount (default 30).
 * {@link DamageModifier}s from the effect registry hook in at each stage.
 */
public final class DamageCalc {
    public static final int WEAKNESS_MULTIPLIER = 2;
    public static final int RESISTANCE_REDUCTION = 30;

    public final Board board;
    public final CardEntity attacker;
    public final CardEntity target;
    public final int base;
    public int amount;
    public final boolean isAttack;
    /** Weakness/Resistance run only versus the defending Active. */
    public final boolean applyModifiers;
    public final String attackTitle;
    public boolean weaknessApplies = true;
    public boolean resistanceApplies = true;
    public int weaknessMultiplier = WEAKNESS_MULTIPLIER;
    public List<Integer> weakTypes;
    public List<Integer> resistTypes;
    public int resistanceAmount;
    public boolean weaknessHit;
    public boolean resistanceHit;
    public boolean prevented;

    public DamageCalc(Board board, CardEntity attacker, CardEntity target, int base, boolean isAttack,
                      boolean applyModifiers, String attackTitle) {
        this.board = board;
        this.attacker = attacker;
        this.target = target;
        this.base = base;
        this.amount = base;
        this.isAttack = isAttack;
        this.applyModifiers = applyModifiers;
        this.attackTitle = attackTitle;
        this.weakTypes = target.intList(Attr.WEAKNESS_TYPES);
        this.resistTypes = new ArrayList<>(target.intList(Attr.RESISTANCE_TYPES));
        resistTypes.removeIf(t -> t < 0);
        int ra = target.getInt(Attr.RESISTANCE_AMOUNT, 0);
        this.resistanceAmount = ra != 0 ? ra : RESISTANCE_REDUCTION;
    }

    public boolean toBench() {
        return target.parent != null && "bench".equals(target.containingAreaName());
    }

    public boolean toActive() {
        return target.parent != null && "activePokemonArea".equals(target.containingAreaName());
    }

    public boolean isOpposing() {
        return attacker != null && !java.util.Objects.equals(attacker.owningPlayerId, target.owningPlayerId);
    }

    /** The full pipeline. */
    public static DamageCalc compute(Board board, CardEntity attacker, CardEntity target, int base, boolean isAttack,
                                     boolean applyModifiers, String attackTitle, List<DamageModifier> modifiers) {
        DamageCalc calc = new DamageCalc(board, attacker, target, base, isAttack, applyModifiers, attackTitle);
        if (calc.isAttack) {
            for (DamageModifier m : modifiers) m.modifyDealt(calc);
            calc.amount = Math.max(0, calc.amount);
        }
        if (calc.applyModifiers && attacker != null) {
            List<Integer> attackerTypes = attacker.intList(Attr.POKEMON_TYPES);
            if (calc.weaknessApplies && attackerTypes.stream().anyMatch(calc.weakTypes::contains)) {
                calc.weaknessHit = true;
                calc.amount *= calc.weaknessMultiplier;
            }
            if (calc.resistanceApplies && attackerTypes.stream().anyMatch(calc.resistTypes::contains)) {
                calc.resistanceHit = true;
                calc.amount = Math.max(0, calc.amount - Math.max(0, calc.resistanceAmount));
            }
        }
        for (DamageModifier m : modifiers) m.modifyTaken(calc);
        calc.amount = Math.max(0, calc.amount);
        for (DamageModifier m : modifiers) {
            if (m.prevents(calc)) {
                calc.prevented = true;
                calc.amount = 0;
                break;
            }
        }
        return calc;
    }
}
