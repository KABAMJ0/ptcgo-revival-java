package com.ptcgo.revival.game.effects;

/** Runs a Trainer/Supporter/Stadium/Tool play effect (after the card is on the table). */
@FunctionalInterface
public interface TrainerEffect {
    void resolve(EffectContext ctx);
}
