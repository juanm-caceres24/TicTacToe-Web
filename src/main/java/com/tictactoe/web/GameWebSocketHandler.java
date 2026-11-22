package com.tictactoe.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tictactoe.engine.Move;
import com.tictactoe.engine.Player;
import com.tictactoe.match.MatchManager;
import com.tictactoe.match.SessionSender;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket handler at {@code /ws} dispatching JSON messages between browser clients and {@link MatchManager}.
 */
public final class GameWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GameWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MatchManager matchManager;
    private final boolean trustProxyHeaders;
    private final ConcurrentHashMap<String, SessionSender> senders = new ConcurrentHashMap<>();

    public GameWebSocketHandler(MatchManager matchManager, boolean trustProxyHeaders) {
        this.matchManager = Objects.requireNonNull(matchManager, "matchManager must not be null");
        this.trustProxyHeaders = trustProxyHeaders;
    }

    public void register(Javalin app) {
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String sessionId = ctx.sessionId();
                String clientIp = extractClientIp(ctx, trustProxyHeaders);
                SessionSender sender = createSender(ctx);
                senders.put(sessionId, sender);
                matchManager.registerSession(sessionId, clientIp, sender);
            });

            ws.onMessage(ctx -> handleTextMessage(ctx, ctx.message()));

            ws.onClose(ctx -> {
                senders.remove(ctx.sessionId());
                matchManager.unregisterSession(ctx.sessionId(), ctx.status());
            });

            ws.onError(ctx -> {
                Throwable err = ctx.error();
                if (err != null) {
                    LOG.debug("WebSocket error on session {}: {}", ctx.sessionId(), err.getMessage());
                }
                senders.remove(ctx.sessionId());
                matchManager.unregisterSession(ctx.sessionId(), null);
            });
        });
    }

    /**
     * Extracts the client IP from the TCP socket address, optionally honoring the leftmost
     * {@code X-Forwarded-For} entry when {@code trustProxyHeaders} is enabled.
     */
    static String resolveClientIp(String socketIp, String forwardedForHeader, boolean trustProxyHeaders) {
        if (trustProxyHeaders && forwardedForHeader != null && !forwardedForHeader.isBlank()) {
            String first = forwardedForHeader.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return (socketIp == null || socketIp.isBlank()) ? "unknown" : socketIp;
    }

    private static String extractClientIp(WsContext ctx, boolean trustProxyHeaders) {
        String socketIp = null;
        SocketAddress remote = ctx.session.getRemoteAddress();
        if (remote instanceof InetSocketAddress inet) {
            socketIp = inet.getAddress() != null
                ? inet.getAddress().getHostAddress()
                : inet.getHostString();
        }
        String forwardedFor = trustProxyHeaders ? ctx.header("X-Forwarded-For") : null;
        return resolveClientIp(socketIp, forwardedFor, trustProxyHeaders);
    }

    private void handleTextMessage(WsContext ctx, String rawMessage) {
        String sessionId = ctx.sessionId();
        JsonNode root;
        try {
            root = MAPPER.readTree(rawMessage);
        } catch (JsonProcessingException e) {
            sendDirectError(ctx, "BAD_REQUEST", "Invalid JSON message.");
            return;
        }

        if (root == null || !root.isObject()) {
            sendDirectError(ctx, "BAD_REQUEST", "Message payload must be a JSON object.");
            return;
        }

        String type = root.path("type").asText("").trim().toUpperCase(Locale.ROOT);
        if (type.isEmpty()) {
            sendDirectError(ctx, "BAD_REQUEST", "Missing required 'type' field.");
            return;
        }

        switch (type) {
            case "JOIN_LOBBY" -> {
                String username = root.has("username") && root.get("username").isTextual()
                    ? root.get("username").asText()
                    : null;
                matchManager.handleJoinLobby(sessionId, username);
            }
            case "QUEUE_ONLINE" -> matchManager.handleQueueOnline(sessionId);
            case "CANCEL_QUEUE" -> matchManager.handleCancelQueue(sessionId);
            case "START_AI_GAME" -> handleStartAiGame(ctx, sessionId, root);
            case "START_LOCAL_GAME" -> matchManager.handleStartLocalGame(sessionId);
            case "PLACE_PIECE" -> handlePlacePiece(ctx, sessionId, root);
            case "MOVE_PIECE" -> handleMovePiece(ctx, sessionId, root);
            case "PLAY_AGAIN" -> matchManager.handlePlayAgain(sessionId);
            case "RETRY_AI_MOVE" -> matchManager.handleRetryAiMove(sessionId);
            case "LEAVE_MATCH" -> matchManager.handleLeaveMatch(sessionId);
            default -> sendDirectError(ctx, "UNKNOWN_MESSAGE_TYPE", "Unsupported message type: '" + type + "'.");
        }
    }

    private void handleStartAiGame(WsContext ctx, String sessionId, JsonNode root) {
        Player playerSymbol = Player.X;
        if (root.has("playerSymbol") && !root.get("playerSymbol").isNull()) {
            if (!root.get("playerSymbol").isTextual()) {
                sendDirectError(ctx, "BAD_REQUEST", "'playerSymbol' must be 'X' or 'O'.");
                return;
            }
            try {
                playerSymbol = Player.fromSymbol(root.get("playerSymbol").asText());
            } catch (IllegalArgumentException e) {
                sendDirectError(ctx, "BAD_REQUEST", e.getMessage());
                return;
            }
        }

        boolean aiMovesFirst = false;
        if (root.has("firstTurn") && !root.get("firstTurn").isNull()) {
            if (!root.get("firstTurn").isTextual()) {
                sendDirectError(ctx, "BAD_REQUEST", "'firstTurn' must be 'HUMAN' or 'AI'.");
                return;
            }
            String firstTurn = root.get("firstTurn").asText().trim().toUpperCase(Locale.ROOT);
            if ("AI".equals(firstTurn)) {
                aiMovesFirst = true;
            } else if (!"HUMAN".equals(firstTurn)) {
                sendDirectError(ctx, "BAD_REQUEST", "Invalid 'firstTurn': expected 'HUMAN' or 'AI'.");
                return;
            }
        }

        matchManager.handleStartAiGame(sessionId, playerSymbol, aiMovesFirst);
    }

    private void handlePlacePiece(WsContext ctx, String sessionId, JsonNode root) {
        JsonNode toNode = root.get("to");
        if (toNode == null || !toNode.isIntegralNumber()) {
            sendDirectError(ctx, "BAD_REQUEST", "PLACE_PIECE requires integer field 'to' in [0..8].");
            return;
        }
        try {
            Move move = Move.placement(toNode.asInt());
            matchManager.handleMove(sessionId, move);
        } catch (IllegalArgumentException e) {
            sendDirectError(ctx, "ILLEGAL_MOVE", e.getMessage());
        }
    }

    private void handleMovePiece(WsContext ctx, String sessionId, JsonNode root) {
        JsonNode fromNode = root.get("from");
        JsonNode toNode = root.get("to");
        if (fromNode == null || !fromNode.isIntegralNumber() || toNode == null || !toNode.isIntegralNumber()) {
            sendDirectError(ctx, "BAD_REQUEST", "MOVE_PIECE requires integer fields 'from' and 'to' in [0..8].");
            return;
        }
        try {
            Move move = Move.movement(fromNode.asInt(), toNode.asInt());
            matchManager.handleMove(sessionId, move);
        } catch (IllegalArgumentException e) {
            sendDirectError(ctx, "ILLEGAL_MOVE", e.getMessage());
        }
    }

    private static SessionSender createSender(WsContext ctx) {
        final Object sendLock = new Object();
        return new SessionSender() {
            @Override
            public void sendJson(Object message) {
                String json;
                try {
                    json = MAPPER.writeValueAsString(message);
                } catch (JsonProcessingException e) {
                    LOG.error("Failed to serialize WebSocket message", e);
                    return;
                }
                synchronized (sendLock) {
                    if (ctx.session.isOpen()) {
                        ctx.send(json);
                    }
                }
            }

            @Override
            public boolean isOpen() {
                return ctx.session.isOpen();
            }
        };
    }

    private void sendDirectError(WsContext ctx, String code, String message) {
        SessionSender sender = senders.get(ctx.sessionId());
        ProtocolMessages.ErrorMessage err = ProtocolMessages.ErrorMessage.of(code, message);
        if (sender != null) {
            sender.sendJson(err);
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(err);
            if (ctx.session.isOpen()) {
                ctx.send(json);
            }
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize error message", e);
        }
    }
}
