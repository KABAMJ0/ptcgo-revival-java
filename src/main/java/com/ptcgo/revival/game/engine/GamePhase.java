package com.ptcgo.revival.game.engine;

/** Game state machine phases (server-side; never sent on the wire). */
public enum GamePhase {
    INIT, COIN_FLIP_WAIT_CHOICE, COIN_FLIP_WAIT_GO_FIRST, DEAL_HANDS, MULLIGAN_PHASE, SETUP_PHASE, PLACEMENT_PHASE,
    PRIZE_DEAL, SETUP_COMPLETE, TURN_LOOP, GAME_OVER
}
