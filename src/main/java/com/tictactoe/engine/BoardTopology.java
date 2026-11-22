package com.tictactoe.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines the fixed 3x3 Mobile Tic-Tac-Toe board topology, winning lines,
 * movement adjacency graph, and canonical 41-action index mapping for the neural network.
 *
 * <pre>
 * 0 — 1 — 2
 * | \ | / |
 * 3 — 4 — 5
 * | / | \ |
 * 6 — 7 — 8
 * </pre>
 *
 * <p>Movement edges connect:
 * <ul>
 *   <li>Every perimeter cell to its two contiguous perimeter neighbors (ring 0-1-2-5-8-7-6-3-0).</li>
 *   <li>Every perimeter cell bidirectionally to the center cell (4).</li>
 * </ul>
 *
 * <p>Canonical action space ({@value #TOTAL_ACTION_COUNT} actions):
 * <ul>
 *   <li>Indices {@code 0..8}: placement onto cell {@code i}.</li>
 *   <li>Indices {@code 9..40}: the {@value #MOVEMENT_ACTION_COUNT} directed legal movement edges
 *       {@code (from -> to)}, sorted lexicographically by {@code from} then {@code to}.</li>
 * </ul>
 */
public final class BoardTopology {

    public static final int BOARD_SIZE = 9;
    public static final int CENTER_CELL = 4;
    public static final int PIECES_PER_PLAYER = 3;

    public static final int PLACEMENT_ACTION_COUNT = 9;
    public static final int MOVEMENT_ACTION_COUNT = 32;
    public static final int TOTAL_ACTION_COUNT = PLACEMENT_ACTION_COUNT + MOVEMENT_ACTION_COUNT;

    private static final int[][] WINNING_LINES = {
        {0, 1, 2},
        {3, 4, 5},
        {6, 7, 8},
        {0, 3, 6},
        {1, 4, 7},
        {2, 5, 8},
        {0, 4, 8},
        {2, 4, 6}
    };

    private static final int[][] ADJACENCY = {
        {1, 3, 4},                   // 0
        {0, 2, 4},                   // 1
        {1, 4, 5},                   // 2
        {0, 4, 6},                   // 3
        {0, 1, 2, 3, 5, 6, 7, 8},    // 4 (center)
        {2, 4, 8},                   // 5
        {3, 4, 7},                   // 6
        {4, 6, 8},                   // 7
        {4, 5, 7}                    // 8
    };

    private static final boolean[][] ADJACENCY_MATRIX = new boolean[BOARD_SIZE][BOARD_SIZE];
    private static final int[][] MOVEMENT_ACTION_INDEX = new int[BOARD_SIZE][BOARD_SIZE];
    private static final Move[] INDEX_TO_MOVE = new Move[TOTAL_ACTION_COUNT];
    private static final List<Move> ALL_ACTIONS;
    private static final List<List<Integer>> NEIGHBOR_LISTS;

    static {
        for (int[] row : MOVEMENT_ACTION_INDEX) {
            Arrays.fill(row, -1);
        }

        for (int cell = 0; cell < PLACEMENT_ACTION_COUNT; cell++) {
            INDEX_TO_MOVE[cell] = Move.placement(cell);
        }

        List<List<Integer>> neighborsBuilder = new ArrayList<>(BOARD_SIZE);
        int actionIndex = PLACEMENT_ACTION_COUNT;
        for (int from = 0; from < BOARD_SIZE; from++) {
            List<Integer> cellNeighbors = new ArrayList<>(ADJACENCY[from].length);
            for (int to : ADJACENCY[from]) {
                ADJACENCY_MATRIX[from][to] = true;
                MOVEMENT_ACTION_INDEX[from][to] = actionIndex;
                INDEX_TO_MOVE[actionIndex] = Move.movement(from, to);
                cellNeighbors.add(to);
                actionIndex++;
            }
            neighborsBuilder.add(List.copyOf(cellNeighbors));
        }

        if (actionIndex != TOTAL_ACTION_COUNT) {
            throw new IllegalStateException("Expected " + TOTAL_ACTION_COUNT + " actions, built " + actionIndex);
        }

        ALL_ACTIONS = List.of(INDEX_TO_MOVE);
        NEIGHBOR_LISTS = List.copyOf(neighborsBuilder);
    }

    private BoardTopology() {
        // Utility class
    }

    /**
     * Returns true if {@code cell} is within {@code 0..8}.
     */
    public static boolean isValidCell(int cell) {
        return cell >= 0 && cell < BOARD_SIZE;
    }

    /**
     * Returns true if a piece can legally slide from {@code from} to {@code to}
     * according to Mobile Tic-Tac-Toe topology.
     */
    public static boolean isAdjacent(int from, int to) {
        if (!isValidCell(from) || !isValidCell(to)) {
            return false;
        }
        return ADJACENCY_MATRIX[from][to];
    }

    /**
     * Returns an unmodifiable sorted list of neighbor cell indices for {@code cell}.
     */
    public static List<Integer> neighbors(int cell) {
        if (!isValidCell(cell)) {
            throw new IllegalArgumentException("Cell index out of bounds [0..8]: " + cell);
        }
        return NEIGHBOR_LISTS.get(cell);
    }

    /**
     * Maps a legal placement or topologically adjacent movement to its canonical action index {@code 0..40}.
     */
    public static int moveToActionIndex(Move move) {
        Objects.requireNonNull(move, "move must not be null");
        if (move.isPlacement()) {
            return move.to();
        }
        int index = MOVEMENT_ACTION_INDEX[move.from()][move.to()];
        if (index < 0) {
            throw new IllegalArgumentException("Cells are not adjacent in board topology: " + move);
        }
        return index;
    }

    /**
     * Maps a canonical action index {@code 0..40} back to its {@link Move}.
     */
    public static Move actionIndexToMove(int actionIndex) {
        if (actionIndex < 0 || actionIndex >= TOTAL_ACTION_COUNT) {
            throw new IllegalArgumentException(
                "Action index out of bounds [0.." + (TOTAL_ACTION_COUNT - 1) + "]: " + actionIndex
            );
        }
        return INDEX_TO_MOVE[actionIndex];
    }

    /**
     * Returns an unmodifiable list of all {@value #TOTAL_ACTION_COUNT} canonical actions in index order.
     */
    public static List<Move> allActions() {
        return ALL_ACTIONS;
    }

    /**
     * Checks whether {@code player} occupies all three cells of any winning line.
     *
     * @return a defensive copy of the winning 3-cell line if present, otherwise {@link Optional#empty()}.
     */
    public static Optional<int[]> findWinningLine(Player[] board, Player player) {
        Objects.requireNonNull(board, "board must not be null");
        Objects.requireNonNull(player, "player must not be null");
        if (board.length != BOARD_SIZE) {
            throw new IllegalArgumentException("Board length must be " + BOARD_SIZE + ", got " + board.length);
        }
        for (int[] line : WINNING_LINES) {
            if (board[line[0]] == player && board[line[1]] == player && board[line[2]] == player) {
                return Optional.of(Arrays.copyOf(line, line.length));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns a defensive copy of all 8 winning lines.
     */
    public static int[][] winningLines() {
        int[][] copy = new int[WINNING_LINES.length][];
        for (int i = 0; i < WINNING_LINES.length; i++) {
            copy[i] = Arrays.copyOf(WINNING_LINES[i], WINNING_LINES[i].length);
        }
        return copy;
    }
}
