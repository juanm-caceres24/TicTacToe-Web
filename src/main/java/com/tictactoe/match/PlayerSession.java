package com.tictactoe.match;

import java.util.Objects;

/**
 * Represents a connected WebSocket player session across lobby, queue, and active match states.
 */
public final class PlayerSession {

    private final String sessionId;
    private final SessionSender sender;

    private volatile String username;
    private volatile String activeMatchId;
    private volatile boolean inQueue;

    public PlayerSession(String sessionId, SessionSender sender) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
    }

    public String sessionId() {
        return sessionId;
    }

    public SessionSender sender() {
        return sender;
    }

    public String username() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean hasUsername() {
        return username != null && !username.isBlank();
    }

    public String activeMatchId() {
        return activeMatchId;
    }

    public void setActiveMatchId(String activeMatchId) {
        this.activeMatchId = activeMatchId;
    }

    public boolean inQueue() {
        return inQueue;
    }

    public void setInQueue(boolean inQueue) {
        this.inQueue = inQueue;
    }

    public void send(Object message) {
        if (sender.isOpen()) {
            sender.sendJson(message);
        }
    }
}
