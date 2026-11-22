package com.tictactoe.match;

import com.tictactoe.ai.AiClientException;
import com.tictactoe.ai.AiPrediction;
import com.tictactoe.ai.AiStatus;
import com.tictactoe.ai.NeuralNetworkClient;
import com.tictactoe.audit.AuditLogger;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.engine.GameEngine;
import com.tictactoe.engine.Move;
import com.tictactoe.engine.Player;
import com.tictactoe.web.ProtocolMessages;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Coordinates player sessions, online FIFO matchmaking, concurrent games across all 3 modes
 * ({@link MatchMode#ONLINE_PVP}, {@link MatchMode#VS_AI}, {@link MatchMode#LOCAL_PVP}),
 * structured JSONL audit logging, and asynchronous AI inference turns without blocking WebSocket I/O threads.
 */
public final class MatchManager implements AutoCloseable {

    private static final Pattern SAFE_USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9 _.-]+$");

    private final ServerConfig config;
    private final NeuralNetworkClient aiClient;
    private final AuditLogger auditLogger;
    private final MatchmakingQueue queue = new MatchmakingQueue();
    private final ConcurrentHashMap<String, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GameMatch> activeMatches = new ConcurrentHashMap<>();
    private final ExecutorService aiExecutor;

    public MatchManager(ServerConfig config, NeuralNetworkClient aiClient) {
        this(config, aiClient, AuditLogger.noop());
    }

    public MatchManager(ServerConfig config, NeuralNetworkClient aiClient, AuditLogger auditLogger) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient must not be null");
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger must not be null");
        AtomicInteger workerId = new AtomicInteger(1);
        this.aiExecutor = Executors.newFixedThreadPool(config.aiServer().workerThreads(), runnable -> {
            Thread thread = new Thread(runnable, "ai-worker-" + workerId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Registers a newly connected WebSocket session with unknown remote IP (primarily for unit tests).
     */
    public PlayerSession registerSession(String sessionId, SessionSender sender) {
        return registerSession(sessionId, "unknown", sender);
    }

    /**
     * Registers a newly connected WebSocket session with its client IP and emits {@code WS_CONNECT}.
     */
    public PlayerSession registerSession(String sessionId, String remoteIp, SessionSender sender) {
        PlayerSession session = new PlayerSession(sessionId, remoteIp, sender);
        sessions.put(sessionId, session);
        auditLogger.logWsConnect(sessionId, session.remoteIp());
        return session;
    }

    /**
     * Cleans up queue membership, notifies any active online opponent, and emits {@code WS_DISCONNECT}.
     */
    public void unregisterSession(String sessionId) {
        unregisterSession(sessionId, null);
    }

    /**
     * Cleans up a disconnecting session, recording the WebSocket close status code when available.
     */
    public void unregisterSession(String sessionId, Integer closeStatus) {
        PlayerSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        queue.remove(session);
        leaveCurrentMatchInternal(session, false, "DISCONNECT");
        auditLogger.logWsDisconnect(
            session.sessionId(),
            session.remoteIp(),
            session.username(),
            session.connectedDurationMs(),
            closeStatus
        );
    }

    /**
     * Validates a username and admits the session into the lobby.
     */
    public void handleJoinLobby(String sessionId, String rawUsername) {
        PlayerSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }

        String sanitized;
        try {
            sanitized = validateUsername(rawUsername, config.minUsernameLength(), config.maxUsernameLength());
        } catch (IllegalArgumentException e) {
            session.send(ProtocolMessages.ErrorMessage.of("INVALID_USERNAME", e.getMessage()));
            return;
        }

        session.setUsername(sanitized);
        auditLogger.logLobbyJoin(session.sessionId(), session.remoteIp(), sanitized);
        AiStatus aiStatus = aiClient.checkStatus();
        session.send(ProtocolMessages.WelcomeMessage.of(sessionId, sanitized, aiStatus));
    }

    /**
     * Enqueues a logged-in player into the online matchmaking queue and starts a match as soon as a pair forms.
     */
    public void handleQueueOnline(String sessionId) {
        PlayerSession session = requireLoggedIn(sessionId);
        if (session == null) {
            return;
        }

        leaveCurrentMatchInternal(session, false, "SWITCH_MODE");

        if (activeMatches.size() >= config.maxConcurrentMatches()) {
            session.send(ProtocolMessages.ErrorMessage.of(
                "SERVER_FULL",
                "Maximum concurrent matches reached (" + config.maxConcurrentMatches() + "). Try again shortly."
            ));
            return;
        }

        Optional<MatchmakingQueue.PairedPlayers> paired = queue.enqueueOrPair(session);
        if (paired.isEmpty()) {
            int pos = Math.max(1, queue.positionOf(session));
            auditLogger.logQueueJoin(session.sessionId(), session.remoteIp(), session.username(), pos);
            session.send(ProtocolMessages.QueueJoinedMessage.of(pos));
            return;
        }

        PlayerSession playerX = paired.get().first();
        PlayerSession playerO = paired.get().second();
        String matchId = generateMatchId();
        GameMatch match = GameMatch.onlinePvp(
            matchId,
            playerX,
            playerO,
            config.repetitionLimit(),
            config.maxMovementPlies()
        );
        activeMatches.put(matchId, match);
        playerX.setActiveMatchId(matchId);
        playerO.setActiveMatchId(matchId);

        logMatchStart(match, false);

        playerX.send(ProtocolMessages.MatchFoundMessage.of(
            matchId, MatchMode.ONLINE_PVP.name(), "X", playerO.username(), match.playerXName(), match.playerOName()
        ));
        playerO.send(ProtocolMessages.MatchFoundMessage.of(
            matchId, MatchMode.ONLINE_PVP.name(), "O", playerX.username(), match.playerXName(), match.playerOName()
        ));
        broadcastGameState(match);
    }

    /**
     * Removes the session from the matchmaking queue.
     */
    public void handleCancelQueue(String sessionId) {
        PlayerSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        if (queue.remove(session)) {
            auditLogger.logQueueCancel(session.sessionId(), session.remoteIp(), session.username());
        }
        session.send(ProtocolMessages.QueueCancelledMessage.instance());
    }

    /**
     * Starts a Player vs AI match after confirming that {@code NeuralNetwork} is reachable and compatible (9->41).
     */
    public void handleStartAiGame(String sessionId, Player playerSymbol, boolean aiMovesFirst) {
        PlayerSession session = requireLoggedIn(sessionId);
        if (session == null) {
            return;
        }

        queue.remove(session);
        leaveCurrentMatchInternal(session, false, "SWITCH_MODE");

        if (activeMatches.size() >= config.maxConcurrentMatches()) {
            session.send(ProtocolMessages.ErrorMessage.of(
                "SERVER_FULL",
                "Maximum concurrent matches reached (" + config.maxConcurrentMatches() + ")."
            ));
            return;
        }

        AiStatus status = aiClient.checkStatus();
        if (!status.isReady()) {
            session.send(ProtocolMessages.ErrorMessage.of(
                "AI_UNAVAILABLE",
                "NeuralNetwork AI server is not ready: " + status.summaryText()
            ));
            return;
        }

        Player effectiveSymbol = playerSymbol != null ? playerSymbol : Player.X;
        Player firstTurn = aiMovesFirst ? effectiveSymbol.opponent() : effectiveSymbol;

        String matchId = generateMatchId();
        GameMatch match = GameMatch.vsAi(
            matchId,
            session,
            effectiveSymbol,
            firstTurn,
            config.repetitionLimit(),
            config.maxMovementPlies()
        );
        activeMatches.put(matchId, match);
        session.setActiveMatchId(matchId);

        logMatchStart(match, false);

        session.send(ProtocolMessages.MatchFoundMessage.of(
            matchId,
            MatchMode.VS_AI.name(),
            String.valueOf(effectiveSymbol.symbol()),
            GameMatch.AI_DISPLAY_NAME,
            match.playerXName(),
            match.playerOName()
        ));

        if (match.shouldTriggerAiTurn()) {
            scheduleAiTurn(match);
        } else {
            broadcastGameState(match);
        }
    }

    /**
     * Starts a Local PvP match (two human players sharing one screen).
     */
    public void handleStartLocalGame(String sessionId) {
        PlayerSession session = requireLoggedIn(sessionId);
        if (session == null) {
            return;
        }

        queue.remove(session);
        leaveCurrentMatchInternal(session, false, "SWITCH_MODE");

        if (activeMatches.size() >= config.maxConcurrentMatches()) {
            session.send(ProtocolMessages.ErrorMessage.of(
                "SERVER_FULL",
                "Maximum concurrent matches reached (" + config.maxConcurrentMatches() + ")."
            ));
            return;
        }

        String matchId = generateMatchId();
        GameMatch match = GameMatch.localPvp(
            matchId,
            session,
            config.repetitionLimit(),
            config.maxMovementPlies()
        );
        activeMatches.put(matchId, match);
        session.setActiveMatchId(matchId);

        logMatchStart(match, false);

        session.send(ProtocolMessages.MatchFoundMessage.of(
            matchId,
            MatchMode.LOCAL_PVP.name(),
            "BOTH",
            "Local Player",
            match.playerXName(),
            match.playerOName()
        ));
        broadcastGameState(match);
    }

    /**
     * Processes a placement or movement request from a connected player.
     */
    public void handleMove(String sessionId, Move move) {
        PlayerSession session = requireLoggedIn(sessionId);
        if (session == null) {
            return;
        }

        String matchId = session.activeMatchId();
        if (matchId == null) {
            session.send(ProtocolMessages.ErrorMessage.of("NO_ACTIVE_MATCH", "You are not currently in a match."));
            return;
        }

        GameMatch match = activeMatches.get(matchId);
        if (match == null || match.isClosed()) {
            session.setActiveMatchId(null);
            session.send(ProtocolMessages.ErrorMessage.of("MATCH_ENDED", "This match is no longer active."));
            return;
        }

        try {
            match.applyHumanMove(sessionId, move);
        } catch (IllegalArgumentException e) {
            session.send(ProtocolMessages.ErrorMessage.of("ILLEGAL_MOVE", e.getMessage()));
            return;
        } catch (IllegalStateException e) {
            session.send(ProtocolMessages.ErrorMessage.of("INVALID_TURN", e.getMessage()));
            return;
        }

        logMatchEndIfTerminal(match);

        if (match.shouldTriggerAiTurn()) {
            // Broadcast the human move immediately (with aiThinking=true) and invoke NeuralNetwork asynchronously.
            scheduleAiTurn(match);
        } else {
            broadcastGameState(match);
        }
    }

    /**
     * Handles a rematch / play-again request from a player in an active match.
     */
    public void handlePlayAgain(String sessionId) {
        PlayerSession session = requireLoggedIn(sessionId);
        if (session == null) {
            return;
        }

        String matchId = session.activeMatchId();
        if (matchId == null) {
            session.send(ProtocolMessages.ErrorMessage.of("NO_ACTIVE_MATCH", "You are not currently in a match."));
            return;
        }

        GameMatch match = activeMatches.get(matchId);
        if (match == null || match.isClosed()) {
            session.setActiveMatchId(null);
            session.send(ProtocolMessages.ErrorMessage.of("MATCH_ENDED", "This match is no longer active."));
            return;
        }

        if (match.mode() == MatchMode.VS_AI) {
            AiStatus status = aiClient.checkStatus();
            if (!status.isReady()) {
                session.send(ProtocolMessages.ErrorMessage.of(
                    "AI_UNAVAILABLE",
                    "NeuralNetwork AI server is not ready: " + status.summaryText()
                ));
                return;
            }
        }

        // Capture pre-reset state in case a mid-game restart happens in VS_AI or LOCAL_PVP.
        GameEngine beforeReset = match.engineSnapshot();
        long priorDurationMs = match.roundDurationMs();

        boolean restarted;
        try {
            restarted = match.requestRematch(sessionId);
        } catch (IllegalStateException e) {
            session.send(ProtocolMessages.ErrorMessage.of("REMATCH_ERROR", e.getMessage()));
            return;
        }

        if (!restarted) {
            match.opponentOf(sessionId)
                .ifPresent(opp -> opp.send(
                    ProtocolMessages.RematchRequestedMessage.of(matchId, session.username())
                ));
            return;
        }

        if (!beforeReset.getStatus().isTerminal()) {
            auditLogger.logMatchEnd(
                match.matchId(),
                match.mode().name(),
                "ABANDONED",
                null,
                beforeReset.getDrawReason().name(),
                beforeReset.getTotalPlies(),
                priorDurationMs,
                "RESTARTED"
            );
        }
        logMatchStart(match, true);

        if (match.shouldTriggerAiTurn()) {
            scheduleAiTurn(match);
        } else {
            broadcastGameState(match);
        }
    }

    /**
     * Retries an AI turn if the previous inference call failed due to a transient AI server error.
     */
    public void handleRetryAiMove(String sessionId) {
        PlayerSession session = requireLoggedIn(sessionId);
        if (session == null) {
            return;
        }
        String matchId = session.activeMatchId();
        if (matchId == null) {
            session.send(ProtocolMessages.ErrorMessage.of("NO_ACTIVE_MATCH", "You are not currently in a match."));
            return;
        }
        GameMatch match = activeMatches.get(matchId);
        if (match == null || match.isClosed()) {
            session.setActiveMatchId(null);
            session.send(ProtocolMessages.ErrorMessage.of("MATCH_ENDED", "This match is no longer active."));
            return;
        }
        if (!match.shouldTriggerAiTurn()) {
            session.send(ProtocolMessages.ErrorMessage.of("INVALID_TURN", "No pending AI turn to retry."));
            return;
        }
        scheduleAiTurn(match);
    }

    /**
     * Removes a player from their current match and informs the opponent if online.
     */
    public void handleLeaveMatch(String sessionId) {
        PlayerSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        leaveCurrentMatchInternal(session, true, "PLAYER_LEFT");
    }

    /**
     * Queries the live external {@code NeuralNetwork} status.
     */
    public AiStatus checkAiStatus() {
        return aiClient.checkStatus();
    }

    public int connectedSessionCount() {
        return sessions.size();
    }

    public int queueSize() {
        return queue.size();
    }

    public int activeMatchCount() {
        return activeMatches.size();
    }

    /**
     * Validates and normalizes a player username using default minimum length 2.
     */
    public static String validateUsername(String raw, int maxLength) {
        return validateUsername(raw, 2, maxLength);
    }

    /**
     * Validates and normalizes a player username (length bounds and XSS-safe character whitelist).
     */
    public static String validateUsername(String raw, int minLength, int maxLength) {
        if (raw == null) {
            throw new IllegalArgumentException("Username is required.");
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        if (collapsed.length() < minLength) {
            throw new IllegalArgumentException(
                "Username must be at least " + minLength + " characters long."
            );
        }
        if (collapsed.length() > maxLength) {
            throw new IllegalArgumentException(
                "Username must be at most " + maxLength + " characters long."
            );
        }
        if (!SAFE_USERNAME_PATTERN.matcher(collapsed).matches()) {
            throw new IllegalArgumentException(
                "Username may only contain letters, numbers, spaces, '_', '-', and '.'."
            );
        }
        return collapsed;
    }

    @Override
    public void close() {
        aiExecutor.shutdownNow();
        for (GameMatch match : activeMatches.values()) {
            logMatchEndOnAbandon(match, "SERVER_SHUTDOWN");
            match.markClosed();
        }
        activeMatches.clear();
        sessions.clear();
    }

    private void scheduleAiTurn(GameMatch match) {
        Optional<GameEngine> snapshotOpt = match.beginAiTurn();
        if (snapshotOpt.isEmpty()) {
            return;
        }
        // Broadcast immediately with aiThinking=true so the UI shows the human move and AI indicator right away.
        broadcastGameState(match);

        GameEngine snapshot = snapshotOpt.get();
        aiExecutor.submit(() -> {
            try {
                AiPrediction prediction = aiClient.predictMove(snapshot);
                if (match.completeAiTurn(prediction)) {
                    logMatchEndIfTerminal(match);
                    broadcastGameState(match);
                }
            } catch (AiClientException e) {
                match.abortAiTurn();
                if (!match.isClosed()) {
                    broadcastGameState(match);
                    ProtocolMessages.ErrorMessage err = ProtocolMessages.ErrorMessage.of(
                        "AI_ERROR",
                        "AI move failed (" + e.reason() + "): " + e.getMessage()
                    );
                    for (PlayerSession participant : match.humanParticipants()) {
                        participant.send(err);
                    }
                }
            } catch (RuntimeException e) {
                match.abortAiTurn();
                if (!match.isClosed()) {
                    broadcastGameState(match);
                    ProtocolMessages.ErrorMessage err = ProtocolMessages.ErrorMessage.of(
                        "AI_ERROR",
                        "Unexpected AI error: " + e.getMessage()
                    );
                    for (PlayerSession participant : match.humanParticipants()) {
                        participant.send(err);
                    }
                }
            }
        });
    }

    private void leaveCurrentMatchInternal(PlayerSession session, boolean confirmToLeaver, String abandonReason) {
        String matchId = session.activeMatchId();
        session.setActiveMatchId(null);
        if (matchId == null) {
            return;
        }

        GameMatch match = activeMatches.remove(matchId);
        if (match == null) {
            return;
        }

        logMatchEndOnAbandon(match, abandonReason);

        Optional<PlayerSession> opponent = match.opponentOf(session.sessionId());
        match.markClosed();

        if (opponent.isPresent()) {
            PlayerSession opp = opponent.get();
            if (matchId.equals(opp.activeMatchId())) {
                opp.setActiveMatchId(null);
            }
            String leaverName = session.hasUsername() ? session.username() : "Opponent";
            opp.send(ProtocolMessages.OpponentLeftMessage.of(
                matchId,
                leaverName + " left the match."
            ));
        }

        if (confirmToLeaver) {
            session.send(ProtocolMessages.MatchLeftMessage.of(matchId));
        }
    }

    private void logMatchStart(GameMatch match, boolean rematch) {
        auditLogger.logMatchStart(
            match.matchId(),
            match.mode().name(),
            match.playerXName(),
            match.playerXIp(),
            match.playerOName(),
            match.playerOIp(),
            String.valueOf(match.startingPlayer().symbol()),
            rematch
        );
    }

    private void logMatchEndIfTerminal(GameMatch match) {
        GameEngine snap = match.engineSnapshot();
        if (!snap.getStatus().isTerminal()) {
            return;
        }
        if (!match.markEndLoggedIfNotYet()) {
            return;
        }
        String winnerName = snap.getWinner()
            .map(w -> w == Player.X ? match.playerXName() : match.playerOName())
            .orElse(null);
        auditLogger.logMatchEnd(
            match.matchId(),
            match.mode().name(),
            snap.getStatus().name(),
            winnerName,
            snap.getDrawReason().name(),
            snap.getTotalPlies(),
            match.roundDurationMs(),
            "COMPLETED"
        );
    }

    private void logMatchEndOnAbandon(GameMatch match, String reason) {
        if (!match.markEndLoggedIfNotYet()) {
            return;
        }
        GameEngine snap = match.engineSnapshot();
        String status = snap.getStatus().isTerminal() ? snap.getStatus().name() : "ABANDONED";
        String winnerName = snap.getWinner()
            .map(w -> w == Player.X ? match.playerXName() : match.playerOName())
            .orElse(null);
        auditLogger.logMatchEnd(
            match.matchId(),
            match.mode().name(),
            status,
            winnerName,
            snap.getDrawReason().name(),
            snap.getTotalPlies(),
            match.roundDurationMs(),
            reason
        );
    }

    private PlayerSession requireLoggedIn(String sessionId) {
        PlayerSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (!session.hasUsername()) {
            session.send(ProtocolMessages.ErrorMessage.of(
                "NOT_JOINED",
                "Enter a username first (JOIN_LOBBY)."
            ));
            return null;
        }
        return session;
    }

    private void broadcastGameState(GameMatch match) {
        ProtocolMessages.GameStateMessage state = ProtocolMessages.GameStateMessage.fromMatch(match);
        for (PlayerSession participant : match.humanParticipants()) {
            participant.send(state);
        }
    }

    private static String generateMatchId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
