package com.ptcgo.revival.game.effects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where card effects plug into the engine, keyed by card guid (Trainers) or by the abilityID the client shows in
 * PIE_ABILITIES (attacks and abilities). The engine never branches on a card: it asks the registry. An empty
 * registry reproduces the Python `unimplemented` behaviour:
 * <ul>
 *   <li>attacks deal their printed damage only;</li>
 *   <li>Trainers/Supporters/Stadiums/Tools are playable and do nothing (the card is discarded, a Stadium stays);</li>
 *   <li>Abilities with no registered effect are not offered at all.</li>
 * </ul>
 */
public final class EffectRegistry {
    public enum Activation { ONCE_PER_TURN, UNLIMITED }

    /** Attack behaviour. A null effect = printed damage only. */
    public record AttackSpec(AttackEffect effect, PlayCondition condition, boolean usableFirstTurn,
                             boolean usableDespiteConditions, boolean usableFromBench) {
        public static AttackSpec of(AttackEffect e) {
            return new AttackSpec(e, null, false, false, false);
        }
    }

    /** Trainer behaviour; {@code playCost} = other hand cards discarded as a play cost. */
    public record TrainerSpec(TrainerEffect effect, PlayCondition condition, TargetProvider targets, int playCost) {
        public static TrainerSpec of(TrainerEffect e) {
            return new TrainerSpec(e, null, null, 0);
        }
    }

    public record AbilitySpec(AbilityEffect effect, PlayCondition condition, Activation activation, boolean endsTurn,
                              boolean vstar) {
        public static AbilitySpec of(AbilityEffect e) {
            return new AbilitySpec(e, null, Activation.ONCE_PER_TURN, false, false);
        }
    }

    private final Map<String, AttackSpec> attacks = new HashMap<>();
    private final Map<String, TrainerSpec> trainers = new HashMap<>();
    private final Map<String, AbilitySpec> abilities = new HashMap<>();
    private final List<DamageModifier> damageModifiers = new CopyOnWriteArrayList<>();

    /** The registry with nothing registered (printed damage only). */
    public static EffectRegistry empty() {
        return new EffectRegistry();
    }

    public EffectRegistry attack(String abilityId, AttackSpec spec) {
        attacks.put(abilityId.toLowerCase(Locale.ROOT), spec);
        return this;
    }

    public EffectRegistry trainer(String cardGuid, TrainerSpec spec) {
        trainers.put(cardGuid.toLowerCase(Locale.ROOT), spec);
        return this;
    }

    public EffectRegistry ability(String abilityId, AbilitySpec spec) {
        abilities.put(abilityId.toLowerCase(Locale.ROOT), spec);
        return this;
    }

    public EffectRegistry damageModifier(DamageModifier m) {
        damageModifiers.add(m);
        return this;
    }

    public AttackSpec attack(String abilityId) {
        return abilityId == null ? null : attacks.get(abilityId.toLowerCase(Locale.ROOT));
    }

    public TrainerSpec trainer(String cardGuid) {
        return cardGuid == null ? null : trainers.get(cardGuid.toLowerCase(Locale.ROOT));
    }

    public AbilitySpec ability(String abilityId) {
        return abilityId == null ? null : abilities.get(abilityId.toLowerCase(Locale.ROOT));
    }

    public List<DamageModifier> damageModifiers() {
        return new ArrayList<>(damageModifiers);
    }
}
