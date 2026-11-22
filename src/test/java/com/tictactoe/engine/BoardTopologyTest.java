package com.tictactoe.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoardTopologyTest {

    @Test
    void centerConnectsBidirectionallyToAllEightPerimeterCells() {
        List<Integer> centerNeighbors = BoardTopology.neighbors(BoardTopology.CENTER_CELL);
        assertEquals(List.of(0, 1, 2, 3, 5, 6, 7, 8), centerNeighbors);

        for (int cell = 0; cell < BoardTopology.BOARD_SIZE; cell++) {
            if (cell == BoardTopology.CENTER_CELL) {
                assertFalse(BoardTopology.isAdjacent(cell, cell));
            } else {
                assertTrue(BoardTopology.isAdjacent(BoardTopology.CENTER_CELL, cell));
                assertTrue(BoardTopology.isAdjacent(cell, BoardTopology.CENTER_CELL));
            }
        }
    }

    @Test
    void perimeterRingConnectivityAndNonAdjacentRejection() {
        int[] ring = {0, 1, 2, 5, 8, 7, 6, 3};
        for (int i = 0; i < ring.length; i++) {
            int cell = ring[i];
            int prev = ring[(i - 1 + ring.length) % ring.length];
            int next = ring[(i + 1) % ring.length];

            List<Integer> neighbors = BoardTopology.neighbors(cell);
            assertEquals(3, neighbors.size(), "Each perimeter cell must have exactly 3 neighbors");
            assertEquals(Set.of(prev, next, BoardTopology.CENTER_CELL), new HashSet<>(neighbors));
        }

        // Verify disallowed perimeter-to-perimeter jumps (e.g., corners and side-to-side shortcuts).
        assertFalse(BoardTopology.isAdjacent(0, 2));
        assertFalse(BoardTopology.isAdjacent(0, 8));
        assertFalse(BoardTopology.isAdjacent(1, 3));
        assertFalse(BoardTopology.isAdjacent(1, 5));
        assertFalse(BoardTopology.isAdjacent(1, 7));
        assertFalse(BoardTopology.isAdjacent(3, 5));
        assertFalse(BoardTopology.isAdjacent(6, 2));
        assertFalse(BoardTopology.isAdjacent(-1, 0));
        assertFalse(BoardTopology.isAdjacent(0, 9));
    }

    @Test
    void adjacencyMatrixIsSymmetricAndHasExactDirectedEdgeCount() {
        int directedEdges = 0;
        for (int from = 0; from < BoardTopology.BOARD_SIZE; from++) {
            assertFalse(BoardTopology.isAdjacent(from, from));
            for (int to = 0; to < BoardTopology.BOARD_SIZE; to++) {
                assertEquals(
                    BoardTopology.isAdjacent(from, to),
                    BoardTopology.isAdjacent(to, from),
                    "Adjacency must be symmetric for (" + from + ", " + to + ")"
                );
                if (BoardTopology.isAdjacent(from, to)) {
                    directedEdges++;
                }
            }
        }
        assertEquals(BoardTopology.MOVEMENT_ACTION_COUNT, directedEdges);
        assertEquals(32, directedEdges);
    }

    @Test
    void canonicalActionIndexMappingIsBijectiveAndLexicographical() {
        List<Move> actions = BoardTopology.allActions();
        assertEquals(BoardTopology.TOTAL_ACTION_COUNT, actions.size());
        assertEquals(41, actions.size());

        // Placement indices 0..8
        for (int cell = 0; cell < 9; cell++) {
            Move expected = Move.placement(cell);
            assertEquals(expected, BoardTopology.actionIndexToMove(cell));
            assertEquals(cell, BoardTopology.moveToActionIndex(expected));
        }

        // Spot-check key canonical movement indices documented for NeuralNetwork
        assertEquals(Move.movement(0, 1), BoardTopology.actionIndexToMove(9));
        assertEquals(Move.movement(0, 4), BoardTopology.actionIndexToMove(11));
        assertEquals(Move.movement(4, 0), BoardTopology.actionIndexToMove(21));
        assertEquals(Move.movement(4, 8), BoardTopology.actionIndexToMove(28));
        assertEquals(Move.movement(8, 7), BoardTopology.actionIndexToMove(40));

        // Verify strict bijection and lexicographical ordering for indices 9..40
        Move previous = null;
        for (int idx = 9; idx < 41; idx++) {
            Move move = BoardTopology.actionIndexToMove(idx);
            assertTrue(move.isMovement());
            assertTrue(BoardTopology.isAdjacent(move.from(), move.to()));
            assertEquals(idx, BoardTopology.moveToActionIndex(move));

            if (previous != null) {
                boolean ordered = move.from() > previous.from()
                    || (move.from() == previous.from() && move.to() > previous.to());
                assertTrue(ordered, "Expected lexicographical order between " + previous + " and " + move);
            }
            previous = move;
        }
    }

    @Test
    void invalidActionAndCellLookupsThrowClearExceptions() {
        assertThrows(IllegalArgumentException.class, () -> BoardTopology.neighbors(-1));
        assertThrows(IllegalArgumentException.class, () -> BoardTopology.neighbors(9));
        assertThrows(IllegalArgumentException.class, () -> BoardTopology.actionIndexToMove(-1));
        assertThrows(IllegalArgumentException.class, () -> BoardTopology.actionIndexToMove(41));
        assertThrows(IllegalArgumentException.class, () -> BoardTopology.moveToActionIndex(Move.movement(0, 2)));
        assertThrows(IllegalArgumentException.class, () -> BoardTopology.moveToActionIndex(Move.movement(1, 3)));
    }

    @Test
    void findWinningLineDetectsAllEightLines() {
        int[][] lines = BoardTopology.winningLines();
        assertEquals(8, lines.length);

        for (int[] line : lines) {
            Player[] board = new Player[9];
            board[line[0]] = Player.X;
            board[line[1]] = Player.X;
            board[line[2]] = Player.X;

            Optional<int[]> found = BoardTopology.findWinningLine(board, Player.X);
            assertTrue(found.isPresent());
            assertArrayEquals(line, found.get());
            assertTrue(BoardTopology.findWinningLine(board, Player.O).isEmpty());
        }
    }
}
