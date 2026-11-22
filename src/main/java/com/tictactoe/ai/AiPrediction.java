package com.tictactoe.ai;

import com.tictactoe.engine.Move;
import com.tictactoe.engine.MoveEncoder;
import java.util.Arrays;
import java.util.Objects;

/**
 * Result of requesting an AI move from the external {@code NeuralNetwork} server.
 *
 * @param selection masked legal move selection and normalized legal probabilities
 * @param latencyUs inference latency in microseconds reported by {@code NeuralNetwork} (-1 if omitted)
 * @param rawOutput raw length-41 output vector returned by {@code POST /predict}
 */
public record AiPrediction(
    MoveEncoder.MaskedSelection selection,
    long latencyUs,
    double[] rawOutput
) {

    public AiPrediction {
        Objects.requireNonNull(selection, "selection must not be null");
        Objects.requireNonNull(rawOutput, "rawOutput must not be null");
        if (rawOutput.length != MoveEncoder.OUTPUT_SIZE) {
            throw new IllegalArgumentException(
                "rawOutput length must be " + MoveEncoder.OUTPUT_SIZE + ", got " + rawOutput.length
            );
        }
        rawOutput = Arrays.copyOf(rawOutput, rawOutput.length);
    }

    /**
     * Convenience accessor for the chosen guaranteed-legal {@link Move}.
     */
    public Move move() {
        return selection.bestMove();
    }

    @Override
    public double[] rawOutput() {
        return Arrays.copyOf(rawOutput, rawOutput.length);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AiPrediction other)) {
            return false;
        }
        return latencyUs == other.latencyUs
            && selection.equals(other.selection)
            && Arrays.equals(rawOutput, other.rawOutput);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(selection, latencyUs);
        result = 31 * result + Arrays.hashCode(rawOutput);
        return result;
    }

    @Override
    public String toString() {
        return "AiPrediction[move=" + selection.bestMove()
            + ", actionIndex=" + selection.bestActionIndex()
            + ", latencyUs=" + latencyUs + "]";
    }
}
