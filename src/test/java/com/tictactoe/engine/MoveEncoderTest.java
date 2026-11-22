package com.tictactoe.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MoveEncoderTest {

    @Test
    void encodeBoardIsPlayerRelativeForBothXAndO() {
        Player[] board = {
            Player.X, null,     Player.O,
            null,     Player.X, null,
            Player.O, null,     null
        };

        double[] fromX = MoveEncoder.encodeBoard(board, Player.X);
        assertArrayEquals(
            new double[] {1.0, 0.0, -1.0, 0.0, 1.0, 0.0, -1.0, 0.0, 0.0},
            fromX,
            1e-9
        );

        double[] fromO = MoveEncoder.encodeBoard(board, Player.O);
        assertArrayEquals(
            new double[] {-1.0, 0.0, 1.0, 0.0, -1.0, 0.0, 1.0, 0.0, 0.0},
            fromO,
            1e-9
        );
    }

    @Test
    void legalActionMaskSeparatesPlacementAndMovementPhases() {
        GameEngine engine = new GameEngine();
        engine.placePiece(0); // X
        engine.placePiece(4); // O

        boolean[] placementMask = MoveEncoder.legalActionMask(engine);
        assertEquals(41, placementMask.length);
        assertFalse(placementMask[0]);
        assertFalse(placementMask[4]);
        for (int i = 0; i < 9; i++) {
            if (i != 0 && i != 4) {
                assertTrue(placementMask[i]);
            }
        }
        for (int i = 9; i < 41; i++) {
            assertFalse(placementMask[i], "Movement indices must be masked out during placement");
        }

        // Now test movement phase mask.
        Player[] board = {
            Player.X, null,     Player.O,
            null,     Player.X, null,
            Player.O, Player.X, Player.O
        };
        GameEngine moveEngine = GameEngine.fromState(board, Player.X);
        boolean[] movementMask = MoveEncoder.legalActionMask(moveEngine);
        for (int i = 0; i < 9; i++) {
            assertFalse(movementMask[i], "Placement indices must be masked out during movement");
        }
        assertTrue(movementMask[BoardTopology.moveToActionIndex(Move.movement(0, 1))]);
        assertTrue(movementMask[BoardTopology.moveToActionIndex(Move.movement(0, 3))]);
        assertTrue(movementMask[BoardTopology.moveToActionIndex(Move.movement(4, 5))]);
        assertFalse(movementMask[BoardTopology.moveToActionIndex(Move.movement(0, 4))]); // occupied by X
        assertFalse(movementMask[BoardTopology.moveToActionIndex(Move.movement(2, 5))]); // belongs to O
    }

    @Test
    void selectBestLegalMoveIgnoresHighScoresOnIllegalActionsAndNormalizesProbabilities() {
        Player[] board = {
            Player.X, null,     Player.O,
            null,     Player.X, null,
            Player.O, Player.X, Player.O
        };
        GameEngine engine = GameEngine.fromState(board, Player.X);

        double[] raw = new double[41];
        // Put huge scores on illegal placements and opponent moves.
        raw[0] = 0.99;
        raw[1] = 0.95;
        raw[BoardTopology.moveToActionIndex(Move.movement(2, 5))] = 0.98;

        // Put valid scores on two legal moves for X.
        int legalIdxA = BoardTopology.moveToActionIndex(Move.movement(0, 3));
        int legalIdxB = BoardTopology.moveToActionIndex(Move.movement(4, 5));
        raw[legalIdxA] = 0.20;
        raw[legalIdxB] = 0.60;

        MoveEncoder.MaskedSelection selection = MoveEncoder.selectBestLegalMove(engine, raw);
        assertEquals(Move.movement(4, 5), selection.bestMove());
        assertEquals(legalIdxB, selection.bestActionIndex());
        assertEquals(0.60, selection.bestScore(), 1e-9);

        double[] probs = selection.normalizedLegalProbabilities();
        assertEquals(0.0, probs[0], 1e-9);
        assertEquals(0.0, probs[1], 1e-9);
        assertEquals(0.25, probs[legalIdxA], 1e-9);
        assertEquals(0.75, probs[legalIdxB], 1e-9);
        assertEquals(1.0, Arrays.stream(probs).sum(), 1e-9);
    }

    @Test
    void selectBestLegalMoveSupportsNegativeLogitsViaStableSoftmax() {
        GameEngine engine = new GameEngine();
        double[] logits = new double[41];
        Arrays.fill(logits, -10.0);
        logits[4] = -1.0; // best legal placement
        logits[0] = -2.0;
        logits[25] = 100.0; // illegal movement during placement

        MoveEncoder.MaskedSelection selection = MoveEncoder.selectBestLegalMove(engine, logits);
        assertEquals(Move.placement(4), selection.bestMove());
        assertEquals(4, selection.bestActionIndex());

        double[] probs = selection.normalizedLegalProbabilities();
        assertEquals(0.0, probs[25], 1e-9);
        assertTrue(probs[4] > probs[0]);
        assertEquals(1.0, Arrays.stream(probs).sum(), 1e-9);
    }

    @Test
    void selectBestLegalMoveHandlesNaNAndInfiniteScoresSafely() {
        GameEngine engine = new GameEngine();
        engine.placePiece(0); // X placed at 0; now O's turn, legal placements are 1..8.

        // Mix of NaN, +Infinity, -Infinity and two finite scores -> finite scores win and preserve exact ratios.
        double[] mixed = new double[41];
        Arrays.fill(mixed, Double.NaN);
        mixed[2] = Double.POSITIVE_INFINITY;
        mixed[3] = Double.NEGATIVE_INFINITY;
        mixed[6] = 0.90;
        mixed[7] = 0.10;
        MoveEncoder.MaskedSelection mixedSelection = MoveEncoder.selectBestLegalMove(engine, mixed);
        assertEquals(Move.placement(6), mixedSelection.bestMove());
        assertEquals(0.90, mixedSelection.bestScore(), 1e-9);
        double[] mixedProbs = mixedSelection.normalizedLegalProbabilities();
        assertEquals(0.0, mixedProbs[2], 1e-9);
        assertEquals(0.0, mixedProbs[3], 1e-9);
        assertEquals(0.90, mixedProbs[6], 1e-9);
        assertEquals(0.10, mixedProbs[7], 1e-9);
        assertEquals(1.0, Arrays.stream(mixedProbs).sum(), 1e-9);

        // All NaN -> deterministic fallback to first legal move (cell 1) with uniform distribution.
        double[] allNaN = new double[41];
        Arrays.fill(allNaN, Double.NaN);
        MoveEncoder.MaskedSelection fallback = MoveEncoder.selectBestLegalMove(engine, allNaN);
        assertEquals(Move.placement(1), fallback.bestMove());
        assertEquals(1, fallback.bestActionIndex());
        assertTrue(Double.isNaN(fallback.bestScore()));
        double[] uniform = fallback.normalizedLegalProbabilities();
        assertEquals(0.0, uniform[0], 1e-9);
        assertEquals(1.0 / 8.0, uniform[1], 1e-9);
        assertEquals(1.0, Arrays.stream(uniform).sum(), 1e-9);

        // All +Infinity -> deterministic fallback to first legal move (cell 1) with uniform distribution.
        double[] allInf = new double[41];
        Arrays.fill(allInf, Double.POSITIVE_INFINITY);
        MoveEncoder.MaskedSelection infFallback = MoveEncoder.selectBestLegalMove(engine, allInf);
        assertEquals(Move.placement(1), infFallback.bestMove());
        assertEquals(1.0 / 8.0, infFallback.normalizedLegalProbabilities()[1], 1e-9);
    }

    @Test
    void selectBestLegalMoveHandlesAllZerosTieBreakingExtremeLogitsAndDefensiveCopies() {
        GameEngine engine = new GameEngine();

        // All zeros -> deterministic tie-break to lowest legal index (0) and uniform distribution.
        double[] zeros = new double[41];
        MoveEncoder.MaskedSelection zeroSel = MoveEncoder.selectBestLegalMove(engine, zeros);
        assertEquals(Move.placement(0), zeroSel.bestMove());
        assertEquals(1.0 / 9.0, zeroSel.normalizedLegalProbabilities()[0], 1e-9);

        // Extreme logits (±1000) must not overflow exp() in stable softmax.
        double[] extreme = new double[41];
        Arrays.fill(extreme, -1000.0);
        extreme[4] = 1000.0;
        extreme[8] = 1000.0; // tie with 4 -> lowest index (4) wins deterministically
        MoveEncoder.MaskedSelection extSel = MoveEncoder.selectBestLegalMove(engine, extreme);
        assertEquals(Move.placement(4), extSel.bestMove());
        double[] extProbs = extSel.normalizedLegalProbabilities();
        assertEquals(0.5, extProbs[4], 1e-9);
        assertEquals(0.5, extProbs[8], 1e-9);
        assertEquals(1.0, Arrays.stream(extProbs).sum(), 1e-9);

        // Mutating returned probability array must not affect internal record state.
        extProbs[4] = 0.0;
        assertEquals(0.5, extSel.normalizedLegalProbabilities()[4], 1e-9);
    }

    @Test
    void selectBestLegalMoveValidatesOutputLengthAndTerminalGames() {
        GameEngine engine = new GameEngine();
        assertThrows(
            IllegalArgumentException.class,
            () -> MoveEncoder.selectBestLegalMove(engine, new double[9])
        );

        // Terminate the game and ensure selection is rejected.
        engine.placePiece(0);
        engine.placePiece(3);
        engine.placePiece(1);
        engine.placePiece(4);
        engine.placePiece(2); // X wins
        assertThrows(
            IllegalStateException.class,
            () -> MoveEncoder.selectBestLegalMove(engine, new double[41])
        );
    }
}
