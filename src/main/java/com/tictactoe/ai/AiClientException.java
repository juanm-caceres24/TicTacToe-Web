package com.tictactoe.ai;

import java.util.Objects;

/**
 * Checked exception raised when communicating with the external {@code NeuralNetwork}
 * HTTP inference server fails or returns an invalid response.
 */
public final class AiClientException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Categorized failure reason for clean backend/UI error handling.
     */
    public enum Reason {
        UNREACHABLE,
        TIMEOUT,
        HTTP_ERROR,
        INVALID_RESPONSE,
        INCOMPATIBLE_MODEL
    }

    private final Reason reason;
    private final int httpStatus;

    public AiClientException(Reason reason, String message) {
        this(reason, -1, message, null);
    }

    public AiClientException(Reason reason, String message, Throwable cause) {
        this(reason, -1, message, cause);
    }

    public AiClientException(Reason reason, int httpStatus, String message) {
        this(reason, httpStatus, message, null);
    }

    public AiClientException(Reason reason, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.httpStatus = httpStatus;
    }

    public Reason reason() {
        return reason;
    }

    /**
     * Returns the HTTP status code when {@link #reason()} is {@link Reason#HTTP_ERROR}, or {@code -1} otherwise.
     */
    public int httpStatus() {
        return httpStatus;
    }
}
