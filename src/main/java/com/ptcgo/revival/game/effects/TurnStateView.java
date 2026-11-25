package com.ptcgo.revival.game.effects;

/** The read-only slice of the turn state conditions and effects may look at. */
public interface TurnStateView {
    int turnNumber();

    String activePlayerId();

    boolean supporterPlayed();

    boolean energyAttached();

    boolean retreated();
}
