package com.tictactoe.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GameEngineTest {

    @Test
    void initialGameStateIsEmptyInPlacementPhase() {
        GameEngine engine = new GameEngine();
        assertEquals(Player.X, engine.getCurrentTurn());
        assertEquals(GamePhase.PLACEMENT, engine.getCurrentPhase());
        assertEquals(GameStatus.IN_PROGRESS, engine.getStatus());
        assertEquals(DrawReason.NONE, engine.getDrawReason());
        assertEquals(0, engine.getPieceCount(Player.X));
        assertEquals(0, engine.getPieceCount(Player.O));
        assertEquals(0, engine.getTotalPlies());
        assertEquals(0, engine.getMovementPlies());
        assertEquals(9, engine.getLegalMoves().size());
        assertTrue(engine.getWinner().isEmpty());
        assertTrue(engine.getWinningLine().isEmpty());
        assertTrue(engine.getLastMove().isEmpty());
        for (int i = 0; i < 9; i++) {
            assertNull(engine.getCell(i));
        }
    }

    @Test
    void placementPhaseTransitionsCleanlyToMovementAfterBothPlayersPlaceThreePieces() {
        GameEngine engine = new GameEngine();

        engine.placePiece(0); // X=1
        engine.placePiece(1); // O=1
        engine.placePiece(2); // X=2
        engine.placePiece(4); // O=2
        engine.placePiece(7); // X=3 (no win)

        // Ply 6: X has 3 pieces, O still has 2 -> O must remain in PLACEMENT phase.
        assertEquals(Player.O, engine.getCurrentTurn());
        assertEquals(GamePhase.PLACEMENT, engine.getCurrentPhase());
        assertEquals(3, engine.getPieceCount(Player.X));
        assertEquals(2, engine.getPieceCount(Player.O));
        assertEquals(0, engine.getCurrentPositionOccurrences());
        assertFalse(engine.isLegalMove(Move.movement(1, 3)));

        engine.placePiece(6); // O=3 (no win: O at 1,4,6; X at 0,2,7)

        // Ply 7: Both players have 3 pieces -> MOVEMENT phase begins, initial position recorded once.
        assertEquals(Player.X, engine.getCurrentTurn());
        assertEquals(GamePhase.MOVEMENT, engine.getCurrentPhase());
        assertEquals(GameStatus.IN_PROGRESS, engine.getStatus());
        assertEquals(6, engine.getTotalPlies());
        assertEquals(0, engine.getMovementPlies());
        assertEquals(1, engine.getCurrentPositionOccurrences());

        // Placement is now illegal; valid sliding moves are legal.
        assertFalse(engine.isLegalMove(Move.placement(3)));
        assertTrue(engine.isLegalMove(Move.movement(0, 3)));
        assertTrue(engine.isLegalMove(Move.movement(2, 5)));
        assertTrue(engine.isLegalMove(Move.movement(7, 8)));

        // Non-adjacent or occupied destination moves are illegal.
        assertFalse(engine.isLegalMove(Move.movement(0, 1))); // occupied by O
        assertFalse(engine.isLegalMove(Move.movement(0, 4))); // occupied by O
        assertFalse(engine.isLegalMove(Move.movement(0, 8))); // non-adjacent
        assertFalse(engine.isLegalMove(Move.movement(1, 3))); // belongs to O
    }

    @Test
    void earlyVictoryInPlacementPhaseStopsGameImmediately() {
        GameEngine engine = new GameEngine();
        engine.placePiece(0); // X
        engine.placePiece(3); // O
        engine.placePiece(1); // X
        engine.placePiece(4); // O
        engine.placePiece(2); // X wins top row {0, 1, 2}

        assertEquals(GameStatus.X_WINS, engine.getStatus());
        assertEquals(Player.X, engine.getWinner().orElseThrow());
        assertArrayEquals(new int[] {0, 1, 2}, engine.getWinningLine().orElseThrow());
        assertTrue(engine.getLegalMoves().isEmpty());
        assertThrows(IllegalStateException.class, () -> engine.placePiece(5));
    }

    @Test
    void movementPhaseAllowsCenterToAnyFreePerimeterAndDetectsWin() {
        // Board: X at {0, 4, 7}, O at {1, 2, 6}, X to move.
        // Moving X from 7 -> 8 completes diagonal {0, 4, 8}.
        Player[] board = {
            Player.X, Player.O, Player.O,
            null,     Player.X, null,
            Player.O, Player.X, null
        };
        GameEngine engine = GameEngine.fromState(board, Player.X);
        assertEquals(GamePhase.MOVEMENT, engine.getCurrentPhase());

        // Center piece (4) can legally slide to every empty perimeter cell: 3, 5, 8.
        assertTrue(engine.isLegalMove(Move.movement(4, 3)));
        assertTrue(engine.isLegalMove(Move.movement(4, 5)));
        assertTrue(engine.isLegalMove(Move.movement(4, 8)));
        assertFalse(engine.isLegalMove(Move.movement(4, 1))); // occupied by O

        engine.movePiece(7, 8);
        assertEquals(GameStatus.X_WINS, engine.getStatus());
        assertEquals(Player.X, engine.getWinner().orElseThrow());
        assertArrayEquals(new int[] {0, 4, 8}, engine.getWinningLine().orElseThrow());
        assertEquals(1, engine.getMovementPlies());
    }

    @Test
    void threefoldRepetitionTriggersDraw() {
        // Non-winning 3v3 setup where X oscillates 0 <-> 3 and O oscillates 2 <-> 5.
        // X at {0, 4, 7}, O at {2, 6, 8} -> wait: {0,4,8} is O/X mixed; no 3-in-a-row for either.
        Player[] board = {
            Player.X, null,     Player.O,
            null,     Player.X, null,
            Player.O, Player.X, Player.O
        };
        GameEngine engine = GameEngine.fromState(board, Player.X, 3, 50);
        assertEquals(1, engine.getCurrentPositionOccurrences());

        // Cycle 1 back to starting position (occurrence 2)
        engine.movePiece(0, 3);
        engine.movePiece(2, 5);
        engine.movePiece(3, 0);
        engine.movePiece(5, 2);
        assertEquals(GameStatus.IN_PROGRESS, engine.getStatus());
        assertEquals(2, engine.getCurrentPositionOccurrences());

        // Cycle 2 back to starting position (occurrence 3 -> DRAW)
        engine.movePiece(0, 3);
        engine.movePiece(2, 5);
        engine.movePiece(3, 0);
        engine.movePiece(5, 2);
        assertEquals(GameStatus.DRAW, engine.getStatus());
        assertEquals(DrawReason.THREEFOLD_REPETITION, engine.getDrawReason());
        assertTrue(engine.getLegalMoves().isEmpty());
    }

    @Test
    void movementPlyLimitTriggersDrawWhenNoWinner() {
        Player[] board = {
            Player.X, null,     Player.O,
            null,     Player.X, null,
            Player.O, Player.X, Player.O
        };
        // High repetition limit so ply limit triggers first.
        GameEngine engine = GameEngine.fromState(board, Player.X, 10, 4);

        engine.movePiece(0, 3); // ply 1
        engine.movePiece(2, 5); // ply 2
        engine.movePiece(3, 0); // ply 3
        assertEquals(GameStatus.IN_PROGRESS, engine.getStatus());

        engine.movePiece(5, 2); // ply 4 -> MOVE_LIMIT_REACHED
        assertEquals(GameStatus.DRAW, engine.getStatus());
        assertEquals(DrawReason.MOVE_LIMIT_REACHED, engine.getDrawReason());
    }

    @Test
    void victoryTakesPrecedenceOverMoveLimitOnFinalAllowedPly() {
        // X at {0, 4, 7}, O at {1, 2, 6}, maxMovementPlies = 1.
        // Moving X from 7 -> 8 wins on movement ply 1 and must NOT be classified as a draw.
        Player[] board = {
            Player.X, Player.O, Player.O,
            null,     Player.X, null,
            Player.O, Player.X, null
        };
        GameEngine engine = GameEngine.fromState(board, Player.X, 3, 1);

        engine.movePiece(7, 8);
        assertEquals(GameStatus.X_WINS, engine.getStatus());
        assertEquals(DrawReason.NONE, engine.getDrawReason());
    }

    @Test
    void copyProducesIndependentEngineAndResetClearsState() {
        GameEngine original = new GameEngine();
        original.placePiece(4);
        original.placePiece(0);

        GameEngine clone = original.copy();
        clone.placePiece(1);

        assertEquals(2, original.getTotalPlies());
        assertEquals(3, clone.getTotalPlies());
        assertNull(original.getCell(1));
        assertEquals(Player.X, clone.getCell(1));

        original.reset(Player.O);
        assertEquals(Player.O, original.getCurrentTurn());
        assertEquals(0, original.getTotalPlies());
        assertEquals(GameStatus.IN_PROGRESS, original.getStatus());
        assertNull(original.getCell(4));
    }

    @Test
    void illegalMovesAndInvalidConfigurationsThrowExpectedExceptions() {
        assertThrows(IllegalArgumentException.class, () -> new GameEngine(Player.X, 1, 50));
        assertThrows(IllegalArgumentException.class, () -> new GameEngine(Player.X, 3, 0));

        GameEngine engine = new GameEngine();
        engine.placePiece(4);
        assertThrows(IllegalArgumentException.class, () -> engine.placePiece(4)); // occupied
        assertThrows(IllegalArgumentException.class, () -> engine.movePiece(4, 0)); // wrong phase

        // Invalid fromState: 4 X pieces
        Player[] tooManyX = {
            Player.X, Player.X, Player.X,
            Player.X, null,     null,
            null,     null,     null
        };
        assertThrows(IllegalArgumentException.class, () -> GameEngine.fromState(tooManyX, Player.O));

        // Invalid fromState: both players winning simultaneously
        Player[] bothWinning = {
            Player.X, Player.X, Player.X,
            Player.O, Player.O, Player.O,
            null,     null,     null
        };
        assertThrows(IllegalArgumentException.class, () -> GameEngine.fromState(bothWinning, Player.X));
    }

    @Test
    void exhaustiveCheckNoNonTerminalThreeVsThreePositionIsEverStalemated() {
        // Exhaustively enumerate all C(9,3) * C(6,3) = 1,680 3-vs-3 placements.
        int nonTerminalChecked = 0;
        for (int x1 = 0; x1 < 9; x1++) {
            for (int x2 = x1 + 1; x2 < 9; x2++) {
                for (int x3 = x2 + 1; x3 < 9; x3++) {
                    for (int o1 = 0; o1 < 9; o1++) {
                        if (o1 == x1 || o1 == x2 || o1 == x3) {
                            continue;
                        }
                        for (int o2 = o1 + 1; o2 < 9; o2++) {
                            if (o2 == x1 || o2 == x2 || o2 == x3) {
                                continue;
                            }
                            for (int o3 = o2 + 1; o3 < 9; o3++) {
                                if (o3 == x1 || o3 == x2 || o3 == x3) {
                                    continue;
                                }
                                Player[] board = new Player[9];
                                board[x1] = Player.X;
                                board[x2] = Player.X;
                                board[x3] = Player.X;
                                board[o1] = Player.O;
                                board[o2] = Player.O;
                                board[o3] = Player.O;

                                boolean xWin = BoardTopology.findWinningLine(board, Player.X).isPresent();
                                boolean oWin = BoardTopology.findWinningLine(board, Player.O).isPresent();
                                if (xWin || oWin) {
                                    continue;
                                }

                                for (Player turn : Player.values()) {
                                    GameEngine engine = GameEngine.fromState(board, turn);
                                    List<Move> legal = engine.getLegalMoves();
                                    assertFalse(
                                        legal.isEmpty(),
                                        "Stalemate found at " + engine.encodePositionKey()
                                    );
                                    nonTerminalChecked++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(nonTerminalChecked > 2000);
    }
}
