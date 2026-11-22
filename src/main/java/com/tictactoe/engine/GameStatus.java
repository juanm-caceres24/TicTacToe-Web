package com.tictactoe.engine;

import java.util.Objects;

/**
 * Current lifecycle status of a Mobile Tic-Tac-Toe game.
 */
public enum GameStatus {
    IN_PROGRESS,
    X_WINS,
    O_WINS,
    DRAW;

    /**
     * Returns true if the game has finished (win or draw).
     */
    public boolean isTerminal() {
        return this != IN_PROGRESS;
    }

    /**
     * Returns the winning status corresponding to the given player.
     */
    public static GameStatus winFor(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        return player == Player.X ? X_WINS : O_WINS;
    }
}
