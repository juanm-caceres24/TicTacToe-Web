package com.tictactoe.ai;

import com.tictactoe.engine.MoveEncoder;

/**
 * Snapshot of external {@code NeuralNetwork} server health and model compatibility.
 */
public record AiStatus(
    boolean serverReachable,
    boolean healthy,
    boolean modelCompatible,
    int inputSize,
    int outputSize,
    int numLayers,
    long parameters,
    String errorMessage
) {

    /**
     * Returns true only when the server is reachable, healthy, and loaded with a compatible 9->41 model.
     */
    public boolean isReady() {
        return serverReachable && healthy && modelCompatible;
    }

    /**
     * Creates an online, compatible status snapshot.
     */
    public static AiStatus ready(int inputSize, int outputSize, int numLayers, long parameters) {
        return new AiStatus(true, true, true, inputSize, outputSize, numLayers, parameters, null);
    }

    /**
     * Creates a status snapshot for a reachable server whose model dimensions do not match 9->41.
     */
    public static AiStatus incompatible(int inputSize, int outputSize, int numLayers, long parameters) {
        String msg = "Incompatible NeuralNetwork model: expected "
            + MoveEncoder.INPUT_SIZE + "->" + MoveEncoder.OUTPUT_SIZE
            + ", got " + inputSize + "->" + outputSize;
        return new AiStatus(true, true, false, inputSize, outputSize, numLayers, parameters, msg);
    }

    /**
     * Creates an offline / unreachable status snapshot.
     */
    public static AiStatus offline(String errorMessage) {
        return new AiStatus(false, false, false, -1, -1, -1, -1L, errorMessage);
    }

    /**
     * Creates a reachable-but-unhealthy status snapshot.
     */
    public static AiStatus unhealthy(String errorMessage) {
        return new AiStatus(true, false, false, -1, -1, -1, -1L, errorMessage);
    }

    /**
     * Concise one-line status summary for startup logs and UI badges.
     */
    public String summaryText() {
        if (isReady()) {
            return "ONLINE | " + inputSize + "->" + outputSize;
        }
        if (serverReachable && healthy && !modelCompatible) {
            return "INCOMPATIBLE | expected " + MoveEncoder.INPUT_SIZE + "->" + MoveEncoder.OUTPUT_SIZE
                + ", got " + inputSize + "->" + outputSize;
        }
        if (serverReachable) {
            return "UNHEALTHY | " + (errorMessage != null ? errorMessage : "unknown error");
        }
        return "OFFLINE | " + (errorMessage != null ? errorMessage : "unreachable");
    }
}
