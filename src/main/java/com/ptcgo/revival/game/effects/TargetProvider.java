package com.ptcgo.revival.game.effects;

import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;

import java.util.List;

/**
 * Declared play targets of an Item/Supporter ("choose one of your Pokemon"): the offer carries one optional
 * EntityListTargetInformation node whose valid targets this returns. An empty list makes the card unplayable.
 */
public interface TargetProvider {
    List<CardEntity> targets(Board board, String playerId, CardEntity card);

    /** LocalizableText id shown as the node's targetPrompt. */
    default String prompt() {
        return "Choose a target";
    }
}
