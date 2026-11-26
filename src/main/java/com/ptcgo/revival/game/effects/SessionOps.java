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

    /**
     * Full-screen card browser (Python {@code prompt_card_chooser}): the player picks up to {@code count} of
     * {@code cards} (at least {@code minimum}, clamped to how many exist); faces ride the offer, so hidden-zone cards
     * need no prior introduction. Returns the picked cards in pick order.
     */
    default List<CardEntity> pickCards(String playerId, String sourceId, List<CardEntity> cards, int count, int minimum,
                                       String prompt) {
        throw new UnsupportedOperationException("pickCards is not supported by this SessionOps");
    }

    /** Maximum HP after any modifiers (the printed max by default). */
    int maxHp(CardEntity pokemon);

    /**
     * "The Defending Pokemon can't retreat during your opponent's next turn" (Python {@code ctx.lock_retreat}). The
     * engine clears the lock when the Pokemon leaves the Active spot.
     */
    default void lockRetreat(CardEntity target) {
        throw new UnsupportedOperationException("lockRetreat is not supported by this SessionOps");
    }
}
