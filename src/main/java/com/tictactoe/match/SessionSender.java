package com.tictactoe.match;

/**
 * Transport-agnostic outbound JSON message channel for a connected client session.
 * Decouples match and matchmaking logic from Javalin/Jetty WebSocket types.
 */
public interface SessionSender {

    /**
     * Serializes {@code message} as JSON and sends it to the connected client if the channel is open.
     */
    void sendJson(Object message);

    /**
     * Returns true if the underlying connection is currently open.
     */
    boolean isOpen();
}
