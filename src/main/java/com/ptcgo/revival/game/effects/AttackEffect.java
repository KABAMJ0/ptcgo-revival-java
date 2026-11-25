package com.ptcgo.revival.game.effects;

/** Runs an attack. The default (no effect / `unimplemented`) deals the printed damage to the opposing Active. */
@FunctionalInterface
public interface AttackEffect {
    void resolve(EffectContext ctx);
}
