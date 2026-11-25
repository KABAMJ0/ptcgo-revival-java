package com.ptcgo.revival.game.effects;

import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;

/**
 * Whether a card/ability/attack may be offered right now. A Trainer with a play cost or requirement MUST carry one:
 * an offered play that cannot be completed soft-locks the client ("May not cancel!").
 */
@FunctionalInterface
public interface PlayCondition {
    boolean test(Board board, TurnStateView turn, String playerId, CardEntity card);
}
