package com.ptcgo.revival.game.effects;

import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;

import java.util.List;
import java.util.random.RandomGenerator;

/** What an {@link EffectContext} may ask of the running match (implemented by the engine's GameSession). */
public interface SessionOps {
    String gameId();

    Board board();

    TurnStateView turn();

    RandomGenerator rng();

    EffectRegistry registry();

    ConditionState conditions();

    void statAdd(String playerId, String key, int amount);

    void statMax(String playerId, String key, int value);

    void creditCardDamage(String playerId, CardEntity attacker, int amount);

    void recordDamage(String targetId, int dealt, boolean byAttack);

    void markHealed(String entityId);

    /** Lets {@code playerId} pick up to {@code count} (at least {@code minimum}) of the candidates. */
    List<CardEntity> pickEntities(String playerId, String sourceId, List<CardEntity> candidates, int count,
                                  int minimum, String prompt);

    /** Maximum HP after any modifiers (the printed max by default). */
    int maxHp(CardEntity pokemon);
}
