package com.tictactoe.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tictactoe.engine.BoardTopology;
import com.tictactoe.engine.GameEngine;
import com.tictactoe.engine.GameStatus;
import com.tictactoe.engine.Move;
import com.tictactoe.engine.MoveEncoder;
import com.tictactoe.engine.Player;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RetrogradeSolverTest {

    private static RetrogradeSolver solver;

    @BeforeAll
    static void setUpSolver() {
        solver = RetrogradeSolver.solve();
    }

    @Test
    void satisfiesBellmanOptimalityEquationAcrossEveryReachableNonTerminalState() {
        assertTrue(solver.reachableStateCount() > 3000);
        assertTrue(solver.nonTerminalStateCount() > 2500);
        assertEquals(
            solver.nonTerminalStateCount(),
            solver.winStateCount() + solver.drawStateCount() + solver.lossStateCount()
        );

        List<RetrogradeSolver.TrainingSample> samples = solver.buildTrainingSamples(RetrogradeSolver.TargetMode.SOFT);
        assertEquals(solver.nonTerminalStateCount(), samples.size());

        for (RetrogradeSolver.TrainingSample sample : samples) {
            int stateId = sample.stateId();
            StateEvaluation eval = solver.evaluateStateId(stateId);
            assertFalse(eval.optimalMoves().isEmpty(), "Non-terminal state must have optimal moves: " + stateId);

            // Reconstruct GameEngine from the player-relative input (X = +1, O = -1, X to move).
            Player[] board = inputToBoard(sample.input());
            GameEngine engine = GameEngine.fromState(board, Player.X);
            List<Move> legalMoves = engine.getLegalMoves();
            assertFalse(legalMoves.isEmpty());

            int minLossSuccDist = Integer.MAX_VALUE;
            int maxWinSuccDist = -1;
            boolean hasDrawSucc = false;
            boolean allWinSucc = true;

            for (Move move : legalMoves) {
                GameEngine child = engine.copy();
                child.applyMove(move);

                StateEvaluation childEval;
                if (child.getStatus() == GameStatus.X_WINS) {
                    // X just won -> from opponent O's perspective it is terminal LOSS with DTM = 0.
                    childEval = new StateEvaluation(StateEvaluation.Outcome.LOSS, 0, List.of());
                } else {
                    childEval = solver.evaluate(child);
                }

                if (childEval.outcome() == StateEvaluation.Outcome.LOSS) {
                    allWinSucc = false;
                    minLossSuccDist = Math.min(minLossSuccDist, childEval.distanceToMate());
                } else if (childEval.outcome() == StateEvaluation.Outcome.DRAW) {
                    allWinSucc = false;
                    hasDrawSucc = true;
                } else {
                    maxWinSuccDist = Math.max(maxWinSuccDist, childEval.distanceToMate());
                }
            }

            switch (eval.outcome()) {
                case WIN -> {
                    assertNotEquals(Integer.MAX_VALUE, minLossSuccDist);
                    assertEquals(minLossSuccDist + 1, eval.distanceToMate());
                    for (Move opt : eval.optimalMoves()) {
                        assertTrue(engine.isLegalMove(opt));
                        GameEngine child = engine.copy();
                        child.applyMove(opt);
                        if (eval.distanceToMate() == 1) {
                            assertEquals(GameStatus.X_WINS, child.getStatus());
                        } else {
                            StateEvaluation childEval = solver.evaluate(child);
                            assertEquals(StateEvaluation.Outcome.LOSS, childEval.outcome());
                            assertEquals(minLossSuccDist, childEval.distanceToMate());
                        }
                    }
                }
                case DRAW -> {
                    assertEquals(Integer.MAX_VALUE, minLossSuccDist);
                    assertTrue(hasDrawSucc);
                    assertEquals(-1, eval.distanceToMate());
                    for (Move opt : eval.optimalMoves()) {
                        assertTrue(engine.isLegalMove(opt));
                        GameEngine child = engine.copy();
                        child.applyMove(opt);
                        assertFalse(child.getStatus().isTerminal());
                        assertEquals(StateEvaluation.Outcome.DRAW, solver.evaluate(child).outcome());
                    }
                }
                case LOSS -> {
                    assertTrue(allWinSucc);
                    assertEquals(maxWinSuccDist + 1, eval.distanceToMate());
                    for (Move opt : eval.optimalMoves()) {
                        assertTrue(engine.isLegalMove(opt));
                        GameEngine child = engine.copy();
                        child.applyMove(opt);
                        StateEvaluation childEval = solver.evaluate(child);
                        assertEquals(StateEvaluation.Outcome.WIN, childEval.outcome());
                        assertEquals(maxWinSuccDist, childEval.distanceToMate());
                    }
                }
            }
        }
    }

    @Test
    void detectsImmediateWinningMovesInBothPlacementAndMovementPhases() {
        // Phase 1: X at {0, 1}, O at {3, 4}, X to move -> PLACE(2) wins in 1 ply.
        Player[] placementWin = {
            Player.X, Player.X, null,
            Player.O, Player.O, null,
            null,     null,     null
        };
        StateEvaluation pEval = solver.evaluate(placementWin, Player.X);
        assertEquals(StateEvaluation.Outcome.WIN, pEval.outcome());
        assertEquals(1, pEval.distanceToMate());
        assertEquals(List.of(Move.placement(2)), pEval.optimalMoves());

        // Phase 2: X at {0, 4, 7}, O at {1, 2, 6}, X to move -> MOVE(7->8) wins in 1 ply on diagonal {0,4,8}.
        Player[] movementWin = {
            Player.X, Player.O, Player.O,
            null,     Player.X, null,
            Player.O, Player.X, null
        };
        StateEvaluation mEval = solver.evaluate(movementWin, Player.X);
        assertEquals(StateEvaluation.Outcome.WIN, mEval.outcome());
        assertEquals(1, mEval.distanceToMate());
        assertTrue(mEval.optimalMoves().contains(Move.movement(7, 8)));
    }

    @Test
    void blocksImmediateOpponentThreatInPlacementPhase() {
        // X at {0, 1}, O at {4}, O to move -> O must block cell 2 immediately.
        Player[] mustBlock = {
            Player.X, Player.X, null,
            null,     Player.O, null,
            null,     null,     null
        };
        StateEvaluation eval = solver.evaluate(mustBlock, Player.O);
        // Any move other than PLACE(2) loses in 1 ply; optimal play must include PLACE(2) only.
        assertEquals(List.of(Move.placement(2)), eval.optimalMoves());
    }

    @Test
    void optimalPlayerNeverLosesAsFirstPlayerAgainstRandomOpponent() {
        // Verify the opening position is WIN or DRAW for X, and playing optimal moves never loses across 100 games.
        StateEvaluation opening = solver.evaluate(new GameEngine());
        assertNotEquals(StateEvaluation.Outcome.LOSS, opening.outcome());

        Random rng = new Random(12345L);
        for (int game = 0; game < 100; game++) {
            GameEngine engine = new GameEngine();
            while (!engine.getStatus().isTerminal()) {
                if (engine.getCurrentTurn() == Player.X) {
                    List<Move> optimal = solver.evaluate(engine).optimalMoves();
                    Move chosen = optimal.get(rng.nextInt(optimal.size()));
                    engine.applyMove(chosen);
                } else {
                    List<Move> legal = engine.getLegalMoves();
                    Move randomMove = legal.get(rng.nextInt(legal.size()));
                    engine.applyMove(randomMove);
                }
            }
            assertNotEquals(GameStatus.O_WINS, engine.getStatus(), "Optimal X lost game #" + game);
        }
    }

    @Test
    void unreachableStateThrowsClearException() {
        // 4 X pieces is illegal and unreachable.
        Player[] impossible = {
            Player.X, Player.X, Player.X,
            Player.X, null,     null,
            null,     null,     null
        };
        assertThrows(IllegalArgumentException.class, () -> solver.evaluate(impossible, Player.O));
        assertThrows(IllegalArgumentException.class, () -> solver.evaluateStateId(-1));
        assertThrows(IllegalArgumentException.class, () -> solver.evaluateStateId(RetrogradeSolver.STATE_SPACE));
    }

    @Test
    void exactTablebaseStatisticsTerminalStatesLossDefenseAndEightFoldSymmetry() {
        // Verify exact documented mathematical figures for Mobile Tic-Tac-Toe.
        assertEquals(3_870, solver.reachableStateCount());
        assertEquals(3_602, solver.nonTerminalStateCount());
        assertEquals(2_161, solver.winStateCount());
        assertEquals(936, solver.drawStateCount());
        assertEquals(505, solver.lossStateCount());

        StateEvaluation opening = solver.evaluate(new GameEngine());
        assertEquals(StateEvaluation.Outcome.WIN, opening.outcome());
        assertEquals(9, opening.distanceToMate());
        assertEquals(List.of(Move.placement(4)), opening.optimalMoves());

        // Terminal lost state (opponent already has 3-in-a-row) -> LOSS, DTM=0, empty optimal moves.
        Player[] terminalLost = {
            Player.O, Player.O, Player.O,
            Player.X, Player.X, null,
            null,     null,     null
        };
        StateEvaluation termEval = solver.evaluate(terminalLost, Player.X);
        assertEquals(StateEvaluation.Outcome.LOSS, termEval.outcome());
        assertEquals(0, termEval.distanceToMate());
        assertTrue(termEval.optimalMoves().isEmpty());

        // Losing state where opponent threatens immediate mate in 1: optimal defense maximizes survival DTM.
        // Verify on every LOSS state that DTM >= 2 whenever at least one move avoids immediate mate-in-1.
        List<RetrogradeSolver.TrainingSample> samples = solver.buildTrainingSamples(RetrogradeSolver.TargetMode.SOFT);
        int lossChecked = 0;
        for (RetrogradeSolver.TrainingSample sample : samples) {
            if (sample.outcome() == StateEvaluation.Outcome.LOSS) {
                StateEvaluation eval = solver.evaluateStateId(sample.stateId());
                assertTrue(eval.distanceToMate() >= 2);
                assertFalse(eval.optimalMoves().isEmpty());
                lossChecked++;
            }
        }
        assertEquals(505, lossChecked);

        // 8-fold dihedral symmetry invariance (4 rotations x 2 reflections) on a sample position.
        int[][] symmetries = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8},
            {6, 3, 0, 7, 4, 1, 8, 5, 2},
            {8, 7, 6, 5, 4, 3, 2, 1, 0},
            {2, 5, 8, 1, 4, 7, 0, 3, 6},
            {2, 1, 0, 5, 4, 3, 8, 7, 6},
            {0, 3, 6, 1, 4, 7, 2, 5, 8},
            {6, 7, 8, 3, 4, 5, 0, 1, 2},
            {8, 5, 2, 7, 4, 1, 6, 3, 0}
        };
        Player[] baseBoard = {
            Player.X, Player.O, null,
            null,     Player.X, null,
            Player.O, null,     null
        };
        StateEvaluation baseEval = solver.evaluate(baseBoard, Player.X);
        for (int[] perm : symmetries) {
            Player[] transformed = new Player[9];
            for (int i = 0; i < 9; i++) {
                transformed[perm[i]] = baseBoard[i];
            }
            StateEvaluation symEval = solver.evaluate(transformed, Player.X);
            assertEquals(baseEval.outcome(), symEval.outcome());
            assertEquals(baseEval.distanceToMate(), symEval.distanceToMate());
            assertEquals(baseEval.optimalMoves().size(), symEval.optimalMoves().size());
        }
    }

    private static Player[] inputToBoard(double[] input) {
        Player[] board = new Player[BoardTopology.BOARD_SIZE];
        for (int i = 0; i < MoveEncoder.INPUT_SIZE; i++) {
            if (input[i] > 0.5) {
                board[i] = Player.X;
            } else if (input[i] < -0.5) {
                board[i] = Player.O;
            }
        }
        return board;
    }
}
