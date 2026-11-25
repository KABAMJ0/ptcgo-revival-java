package com.ptcgo.revival.game.engine;

/** Thrown once the game has been decided; unwinds the gameplay sequence. */
public final class GameOverException extends RuntimeException {
    public GameOverException() {
        super("game over", null, false, false);
    }
}
