package com.ptcgo.revival.game.effects;

/** Runs an activated Ability. */
@FunctionalInterface
public interface AbilityEffect {
    void resolve(EffectContext ctx);
}
