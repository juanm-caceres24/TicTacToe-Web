package com.tictactoe.engine;

/**
 * Active turn phase for the current player in Mobile Tic-Tac-Toe.
 */
public enum GamePhase {
    /**
     * The current player has fewer than 3 pieces on the board and must place a new piece on an empty cell.
     */
    PLACEMENT,

    /**
     * The current player has 3 pieces on the board and must slide one owned piece to an adjacent empty cell.
     */
    MOVEMENT
}
