package com.ptcgo.revival.game.effects;

/**
 * A continuous damage rewrite (the damage-related hooks of ptcgo's Passive). Stages run in this order inside
 * {@link DamageCalc#compute}: {@link #modifyDealt} (attacker side, before weakness/resistance),
 * weakness/resistance, {@link #modifyTaken} (defender side), then {@link #prevents}.
 */
public interface DamageModifier {
    default void modifyDealt(DamageCalc calc) {}

    default void modifyTaken(DamageCalc calc) {}

    default boolean prevents(DamageCalc calc) {
        return false;
    }
}
