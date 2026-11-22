package com.tictactoe.engine;

/**
 * Immutable representation of a move in Mobile Tic-Tac-Toe.
 *
 * <p>During {@link GamePhase#PLACEMENT}, {@code from} is {@link #PLACEMENT_ORIGIN} ({@code -1})
 * and {@code to} is the target cell {@code 0..8}.
 * During {@link GamePhase#MOVEMENT}, {@code from} is the origin cell {@code 0..8}
 * and {@code to} is a different destination cell {@code 0..8}.
 */
public record Move(int from, int to) {

    /**
     * Sentinel origin index indicating a piece placement rather than a slide movement.
     */
    public static final int PLACEMENT_ORIGIN = -1;

    public Move {
        if (!BoardTopology.isValidCell(to)) {
            throw new IllegalArgumentException("Destination cell out of bounds [0..8]: " + to);
        }
        if (from != PLACEMENT_ORIGIN) {
            if (!BoardTopology.isValidCell(from)) {
                throw new IllegalArgumentException("Origin cell out of bounds [0..8]: " + from);
            }
            if (from == to) {
                throw new IllegalArgumentException("Movement origin and destination must differ: " + from);
            }
        }
    }

    /**
     * Creates a placement move onto the specified cell.
     */
    public static Move placement(int to) {
        return new Move(PLACEMENT_ORIGIN, to);
    }

    /**
     * Creates a movement move from an origin cell to a destination cell.
     */
    public static Move movement(int from, int to) {
        return new Move(from, to);
    }

    /**
     * Returns true if this move places a new piece onto the board.
     */
    public boolean isPlacement() {
        return from == PLACEMENT_ORIGIN;
    }

    /**
     * Returns true if this move slides an existing piece between cells.
     */
    public boolean isMovement() {
        return from != PLACEMENT_ORIGIN;
    }

    @Override
    public String toString() {
        if (isPlacement()) {
            return "PLACE(" + to + ")";
        }
        return "MOVE(" + from + "->" + to + ")";
    }
}
