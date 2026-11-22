package com.tictactoe.engine;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Encodes Mobile Tic-Tac-Toe board states for the external {@code NeuralNetwork} service
 * ({@value #INPUT_SIZE} player-relative inputs) and performs legal move masking over its
 * {@value #OUTPUT_SIZE}-element policy output vector.
 */
public final class MoveEncoder {

    public static final int INPUT_SIZE = BoardTopology.BOARD_SIZE;
    public static final int OUTPUT_SIZE = BoardTopology.TOTAL_ACTION_COUNT;

    private MoveEncoder() {
        // Utility class
    }

    /**
     * Result of applying legal move masking to a raw 41-element network output vector.
     *
     * @param bestMove                     selected legal move (guaranteed legal)
     * @param bestActionIndex              canonical action index {@code 0..40}
     * @param bestScore                    raw network score at {@code bestActionIndex}
     * @param normalizedLegalProbabilities length-41 array with 0.0 on illegal actions and a valid
     *                                     probability distribution summing to 1.0 over legal actions
     */
    public record MaskedSelection(
        Move bestMove,
        int bestActionIndex,
        double bestScore,
        double[] normalizedLegalProbabilities
    ) {
        public MaskedSelection {
            Objects.requireNonNull(bestMove, "bestMove must not be null");
            Objects.requireNonNull(normalizedLegalProbabilities, "normalizedLegalProbabilities must not be null");
            if (normalizedLegalProbabilities.length != OUTPUT_SIZE) {
                throw new IllegalArgumentException(
                    "normalizedLegalProbabilities length must be " + OUTPUT_SIZE
                        + ", got " + normalizedLegalProbabilities.length
                );
            }
            normalizedLegalProbabilities = Arrays.copyOf(normalizedLegalProbabilities, OUTPUT_SIZE);
        }

        @Override
        public double[] normalizedLegalProbabilities() {
            return Arrays.copyOf(normalizedLegalProbabilities, normalizedLegalProbabilities.length);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MaskedSelection other)) {
                return false;
            }
            return bestActionIndex == other.bestActionIndex
                && Double.compare(bestScore, other.bestScore) == 0
                && bestMove.equals(other.bestMove)
                && Arrays.equals(normalizedLegalProbabilities, other.normalizedLegalProbabilities);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(bestMove, bestActionIndex, bestScore);
            result = 31 * result + Arrays.hashCode(normalizedLegalProbabilities);
            return result;
        }

        @Override
        public String toString() {
            return "MaskedSelection[bestMove=" + bestMove
                + ", bestActionIndex=" + bestActionIndex
                + ", bestScore=" + bestScore
                + ", normalizedLegalProbabilities=" + Arrays.toString(normalizedLegalProbabilities) + "]";
        }
    }

    /**
     * Encodes the current engine state relative to {@link GameEngine#getCurrentTurn()}.
     *
     * @return length-9 array where {@code +1.0} is current player, {@code -1.0} is opponent, {@code 0.0} is empty.
     */
    public static double[] encodeBoard(GameEngine engine) {
        Objects.requireNonNull(engine, "engine must not be null");
        return encodeBoard(engine.getBoardCopy(), engine.getCurrentTurn());
    }

    /**
     * Encodes a board array from the perspective of {@code perspective}.
     *
     * @return length-9 array where {@code +1.0} is {@code perspective}, {@code -1.0} is opponent, {@code 0.0} is empty.
     */
    public static double[] encodeBoard(Player[] board, Player perspective) {
        Objects.requireNonNull(board, "board must not be null");
        Objects.requireNonNull(perspective, "perspective must not be null");
        if (board.length != INPUT_SIZE) {
            throw new IllegalArgumentException("Board length must be " + INPUT_SIZE + ", got " + board.length);
        }
        double[] encoded = new double[INPUT_SIZE];
        for (int i = 0; i < INPUT_SIZE; i++) {
            if (board[i] == null) {
                encoded[i] = 0.0;
            } else if (board[i] == perspective) {
                encoded[i] = 1.0;
            } else {
                encoded[i] = -1.0;
            }
        }
        return encoded;
    }

    /**
     * Builds a boolean mask of length {@value #OUTPUT_SIZE} where {@code true} marks legal actions.
     */
    public static boolean[] legalActionMask(GameEngine engine) {
        Objects.requireNonNull(engine, "engine must not be null");
        boolean[] mask = new boolean[OUTPUT_SIZE];
        for (Move move : engine.getLegalMoves()) {
            mask[BoardTopology.moveToActionIndex(move)] = true;
        }
        return mask;
    }

    /**
     * Applies legal move masking over a raw {@value #OUTPUT_SIZE}-element score vector and selects
     * the legal move with the highest score.
     *
     * <p>Resilient against {@code NaN} or infinite outputs: invalid values are never preferred over
     * finite scores, and if all legal scores are {@code NaN} or {@code -Infinity}, deterministically
     * falls back to the first legal move in canonical order.
     */
    public static MaskedSelection selectBestLegalMove(GameEngine engine, double[] rawScores) {
        Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(rawScores, "rawScores must not be null");
        if (rawScores.length != OUTPUT_SIZE) {
            throw new IllegalArgumentException(
                "rawScores length must be " + OUTPUT_SIZE + ", got " + rawScores.length
            );
        }
        if (engine.getStatus().isTerminal()) {
            throw new IllegalStateException("Cannot select move for a terminated game: " + engine.getStatus());
        }

        List<Move> legalMoves = engine.getLegalMoves();
        if (legalMoves.isEmpty()) {
            throw new IllegalStateException("No legal moves available in non-terminal state");
        }

        int[] legalIndices = new int[legalMoves.size()];
        for (int i = 0; i < legalMoves.size(); i++) {
            legalIndices[i] = BoardTopology.moveToActionIndex(legalMoves.get(i));
        }

        int bestPos = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < legalIndices.length; i++) {
            double score = rawScores[legalIndices[i]];
            if (Double.isNaN(score)) {
                continue;
            }
            if (bestPos == -1 || score > bestScore) {
                bestPos = i;
                bestScore = score;
            }
        }

        if (bestPos == -1) {
            // All legal scores were NaN; fall back deterministically to the first legal move.
            bestPos = 0;
            bestScore = rawScores[legalIndices[0]];
        }

        double[] normalized = normalizeLegalScores(legalIndices, rawScores);
        return new MaskedSelection(
            legalMoves.get(bestPos),
            legalIndices[bestPos],
            bestScore,
            normalized
        );
    }

    private static double[] normalizeLegalScores(int[] legalIndices, double[] rawScores) {
        double[] normalized = new double[OUTPUT_SIZE];
        boolean allFinite = true;
        boolean allNonNegative = true;
        double positiveSum = 0.0;
        double maxScore = Double.NEGATIVE_INFINITY;

        for (int idx : legalIndices) {
            double s = rawScores[idx];
            if (!Double.isFinite(s)) {
                allFinite = false;
                break;
            }
            if (s < 0.0) {
                allNonNegative = false;
            }
            positiveSum += s;
            if (s > maxScore) {
                maxScore = s;
            }
        }

        if (!allFinite) {
            // Count positive-infinity overrides first; otherwise fall back to uniform distribution.
            int posInfCount = 0;
            for (int idx : legalIndices) {
                if (rawScores[idx] == Double.POSITIVE_INFINITY) {
                    posInfCount++;
                }
            }
            if (posInfCount > 0) {
                double share = 1.0 / posInfCount;
                for (int idx : legalIndices) {
                    if (rawScores[idx] == Double.POSITIVE_INFINITY) {
                        normalized[idx] = share;
                    }
                }
            } else {
                double uniform = 1.0 / legalIndices.length;
                for (int idx : legalIndices) {
                    normalized[idx] = uniform;
                }
            }
            return normalized;
        }

        if (allNonNegative && positiveSum > 0.0) {
            for (int idx : legalIndices) {
                normalized[idx] = rawScores[idx] / positiveSum;
            }
            return normalized;
        }

        // Raw logits (contain negatives or all zeros): apply numerically stable softmax over legal actions.
        double expSum = 0.0;
        for (int idx : legalIndices) {
            double e = Math.exp(rawScores[idx] - maxScore);
            normalized[idx] = e;
            expSum += e;
        }
        for (int idx : legalIndices) {
            normalized[idx] /= expSum;
        }
        return normalized;
    }
}
