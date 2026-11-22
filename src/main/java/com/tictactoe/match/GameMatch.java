package com.tictactoe.match;

import com.tictactoe.ai.AiPrediction;
import com.tictactoe.engine.GameEngine;
import com.tictactoe.engine.Move;
import com.tictactoe.engine.Player;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Thread-safe match instance wrapping a {@link GameEngine}, participating sessions,
 * AI turn state, and rematch voting.
 */
public final class GameMatch {

    public static final String AI_DISPLAY_NAME = "NeuralNetwork AI";

    private final String matchId;
    private final MatchMode mode;
    private final GameEngine engine;
    private final PlayerSession playerX;
    private final PlayerSession playerO;
    private final String playerXName;
    private final String playerOName;
    private final Player aiSymbol;
    private final Set<String> rematchVotes = new HashSet<>();

    private Player startingPlayer;
    private boolean aiThinking;
    private AiPrediction lastAiPrediction;
    private boolean closed;

    private GameMatch(
        String matchId,
        MatchMode mode,
        GameEngine engine,
        Player startingPlayer,
        PlayerSession playerX,
        PlayerSession playerO,
        String playerXName,
        String playerOName,
        Player aiSymbol
    ) {
        this.matchId = Objects.requireNonNull(matchId, "matchId must not be null");
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.startingPlayer = Objects.requireNonNull(startingPlayer, "startingPlayer must not be null");
        this.playerX = playerX;
        this.playerO = playerO;
        this.playerXName = Objects.requireNonNull(playerXName, "playerXName must not be null");
        this.playerOName = Objects.requireNonNull(playerOName, "playerOName must not be null");
        this.aiSymbol = aiSymbol;
    }

    public static GameMatch onlinePvp(
        String matchId,
        PlayerSession playerX,
        PlayerSession playerO,
        int repetitionLimit,
        int maxMovementPlies
    ) {
        GameEngine engine = new GameEngine(Player.X, repetitionLimit, maxMovementPlies);
        return new GameMatch(
            matchId,
            MatchMode.ONLINE_PVP,
            engine,
            Player.X,
            playerX,
            playerO,
            playerX.username(),
            playerO.username(),
            null
        );
    }

    public static GameMatch vsAi(
        String matchId,
        PlayerSession human,
        Player humanSymbol,
        Player firstTurn,
        int repetitionLimit,
        int maxMovementPlies
    ) {
        Objects.requireNonNull(humanSymbol, "humanSymbol must not be null");
        Objects.requireNonNull(firstTurn, "firstTurn must not be null");
        GameEngine engine = new GameEngine(firstTurn, repetitionLimit, maxMovementPlies);
        Player ai = humanSymbol.opponent();
        PlayerSession px = humanSymbol == Player.X ? human : null;
        PlayerSession po = humanSymbol == Player.O ? human : null;
        String pxName = humanSymbol == Player.X ? human.username() : AI_DISPLAY_NAME;
        String poName = humanSymbol == Player.O ? human.username() : AI_DISPLAY_NAME;
        return new GameMatch(matchId, MatchMode.VS_AI, engine, firstTurn, px, po, pxName, poName, ai);
    }

    public static GameMatch localPvp(
        String matchId,
        PlayerSession hostSession,
        int repetitionLimit,
        int maxMovementPlies
    ) {
        GameEngine engine = new GameEngine(Player.X, repetitionLimit, maxMovementPlies);
        return new GameMatch(
            matchId,
            MatchMode.LOCAL_PVP,
            engine,
            Player.X,
            hostSession,
            hostSession,
            "Player X",
            "Player O",
            null
        );
    }

    public String matchId() {
        return matchId;
    }

    public MatchMode mode() {
        return mode;
    }

    public String playerXName() {
        return playerXName;
    }

    public String playerOName() {
        return playerOName;
    }

    public Optional<Player> aiSymbol() {
        return Optional.ofNullable(aiSymbol);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized void markClosed() {
        this.closed = true;
        this.aiThinking = false;
    }

    public synchronized boolean isAiThinking() {
        return aiThinking;
    }

    public synchronized boolean isParticipant(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        return (playerX != null && playerX.sessionId().equals(sessionId))
            || (playerO != null && playerO.sessionId().equals(sessionId));
    }

    public synchronized Optional<Player> symbolFor(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        if (mode == MatchMode.LOCAL_PVP && playerX != null && playerX.sessionId().equals(sessionId)) {
            return Optional.of(engine.getCurrentTurn());
        }
        if (playerX != null && playerX.sessionId().equals(sessionId)) {
            return Optional.of(Player.X);
        }
        if (playerO != null && playerO.sessionId().equals(sessionId)) {
            return Optional.of(Player.O);
        }
        return Optional.empty();
    }

    public synchronized Optional<PlayerSession> opponentOf(String sessionId) {
        if (mode != MatchMode.ONLINE_PVP) {
            return Optional.empty();
        }
        if (playerX != null && playerX.sessionId().equals(sessionId)) {
            return Optional.ofNullable(playerO);
        }
        if (playerO != null && playerO.sessionId().equals(sessionId)) {
            return Optional.ofNullable(playerX);
        }
        return Optional.empty();
    }

    public synchronized List<PlayerSession> humanParticipants() {
        if (mode == MatchMode.LOCAL_PVP) {
            return playerX != null ? List.of(playerX) : List.of();
        }
        if (playerX != null && playerO != null) {
            return List.of(playerX, playerO);
        }
        if (playerX != null) {
            return List.of(playerX);
        }
        if (playerO != null) {
            return List.of(playerO);
        }
        return List.of();
    }

    /**
     * Validates turn ownership and applies a human move to the backend {@link GameEngine}.
     */
    public synchronized void applyHumanMove(String sessionId, Move move) {
        if (closed) {
            throw new IllegalStateException("Match is no longer active");
        }
        if (aiThinking) {
            throw new IllegalStateException("Wait for the AI to complete its turn");
        }
        if (engine.getStatus().isTerminal()) {
            throw new IllegalStateException("Match has already ended");
        }

        Player expectedTurn = engine.getCurrentTurn();
        if (mode == MatchMode.LOCAL_PVP) {
            if (!isParticipant(sessionId)) {
                throw new IllegalArgumentException("Session is not part of this match");
            }
        } else {
            Player assigned = symbolFor(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session is not part of this match"));
            if (assigned != expectedTurn) {
                throw new IllegalStateException("It is currently " + expectedTurn + "'s turn");
            }
        }

        engine.applyMove(move);
        lastAiPrediction = null;
    }

    /**
     * Returns true if the external AI should move next and no AI request is already running.
     */
    public synchronized boolean shouldTriggerAiTurn() {
        return mode == MatchMode.VS_AI
            && !closed
            && !aiThinking
            && !engine.getStatus().isTerminal()
            && engine.getCurrentTurn() == aiSymbol;
    }

    /**
     * Atomically marks AI thinking state and returns an independent {@link GameEngine} copy
     * so network I/O runs outside the match lock.
     */
    public synchronized Optional<GameEngine> beginAiTurn() {
        if (!shouldTriggerAiTurn()) {
            return Optional.empty();
        }
        aiThinking = true;
        return Optional.of(engine.copy());
    }

    /**
     * Completes an in-flight AI turn with the masked move returned by {@code NeuralNetworkClient}.
     *
     * @return true if the prediction was applied (false if the match closed or reset while in flight)
     */
    public synchronized boolean completeAiTurn(AiPrediction prediction) {
        aiThinking = false;
        if (closed || engine.getStatus().isTerminal() || engine.getCurrentTurn() != aiSymbol) {
            return false;
        }
        engine.applyMove(prediction.move());
        lastAiPrediction = prediction;
        return true;
    }

    /**
     * Clears the AI thinking flag after a failed network request without closing the match.
     */
    public synchronized void abortAiTurn() {
        aiThinking = false;
    }

    /**
     * Registers a rematch request. For {@code VS_AI} and {@code LOCAL_PVP}, resets immediately.
     * For {@code ONLINE_PVP}, resets once both participants have requested a rematch (alternating starter).
     *
     * @return true if the match was reset and is ready to play again; false if waiting for opponent vote
     */
    public synchronized boolean requestRematch(String sessionId) {
        if (closed || !isParticipant(sessionId)) {
            throw new IllegalStateException("Cannot request rematch for inactive match");
        }
        if (aiThinking) {
            throw new IllegalStateException("Wait for the AI request to finish before restarting");
        }

        if (mode == MatchMode.ONLINE_PVP) {
            rematchVotes.add(sessionId);
            if (rematchVotes.size() < 2) {
                return false;
            }
            rematchVotes.clear();
            startingPlayer = startingPlayer.opponent();
            engine.reset(startingPlayer);
            lastAiPrediction = null;
            return true;
        }

        rematchVotes.clear();
        engine.reset(startingPlayer);
        lastAiPrediction = null;
        return true;
    }

    public synchronized GameEngine engineSnapshot() {
        return engine.copy();
    }

    public synchronized Optional<AiPrediction> lastAiPrediction() {
        return Optional.ofNullable(lastAiPrediction);
    }
}
