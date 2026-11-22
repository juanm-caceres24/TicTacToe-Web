package com.tictactoe.engine;

/**
 * Explains why a Mobile Tic-Tac-Toe game ended in a draw.
 */
public enum DrawReason {
    /**
     * Game is not a draw.
     */
    NONE,

    /**
     * The exact same board and side-to-move position repeated the configured number of times.
     */
    THREEFOLD_REPETITION,

    /**
     * The maximum number of movement plies was reached without a winner.
     */
    MOVE_LIMIT_REACHED
}
