package com.tictactoe.web;

import com.tictactoe.ai.AiPrediction;
import com.tictactoe.ai.AiStatus;
import com.tictactoe.engine.BoardTopology;
import com.tictactoe.engine.GameEngine;
import com.tictactoe.engine.Move;
import com.tictactoe.engine.Player;
import com.tictactoe.match.GameMatch;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed JSON DTOs for REST API responses and WebSocket server-to-client messages.
 */
public final class ProtocolMessages {

    private ProtocolMessages() {
        // DTO namespace
    }

    public record AiStatusDto(
        boolean available,
        boolean serverReachable,
        boolean healthy,
        boolean compatible,
        int inputSize,
        int outputSize,
        int numLayers,
        long parameters,
        String summary,
        String error
    ) {
        public static AiStatusDto from(AiStatus status) {
            return new AiStatusDto(
                status.isReady(),
                status.serverReachable(),
                status.healthy(),
                status.modelCompatible(),
                status.inputSize(),
                status.outputSize(),
                status.numLayers(),
                status.parameters(),
                status.summaryText(),
                status.errorMessage()
            );
        }
    }

    public record HealthResponse(
        String status,
        String version,
        int connectedSessions,
        int queueSize,
        int activeMatches
    ) {
    }

    public record PublicConfigResponse(
        String version,
        int minUsernameLength,
        int maxUsernameLength,
        int repetitionLimit,
        int maxMovementPlies
    ) {
    }

    public record MoveDto(int from, int to, int actionIndex) {
        public static MoveDto from(Move move) {
            return new MoveDto(move.from(), move.to(), BoardTopology.moveToActionIndex(move));
        }
    }

    public record AiMetaDto(long latencyUs, double[] actionProbs) {
        public AiMetaDto {
            actionProbs = actionProbs == null ? null : Arrays.copyOf(actionProbs, actionProbs.length);
        }

        @Override
        public double[] actionProbs() {
            return actionProbs == null ? null : Arrays.copyOf(actionProbs, actionProbs.length);
        }
    }

    public record WelcomeMessage(
        String type,
        String sessionId,
        String username,
        AiStatusDto aiStatus
    ) {
        public static WelcomeMessage of(String sessionId, String username, AiStatus status) {
            return new WelcomeMessage("WELCOME", sessionId, username, AiStatusDto.from(status));
        }
    }

    public record QueueJoinedMessage(String type, int position) {
        public static QueueJoinedMessage of(int position) {
            return new QueueJoinedMessage("QUEUE_JOINED", position);
        }
    }

    public record QueueCancelledMessage(String type) {
        public static QueueCancelledMessage instance() {
            return new QueueCancelledMessage("QUEUE_CANCELLED");
        }
    }

    public record MatchFoundMessage(
        String type,
        String matchId,
        String mode,
        String yourSymbol,
        String opponent,
        String playerX,
        String playerO
    ) {
        public static MatchFoundMessage of(
            String matchId,
            String mode,
            String yourSymbol,
            String opponent,
            String playerX,
            String playerO
        ) {
            return new MatchFoundMessage("MATCH_FOUND", matchId, mode, yourSymbol, opponent, playerX, playerO);
        }
    }

    public record GameStateMessage(
        String type,
        String matchId,
        String mode,
        String[] board,
        String phase,
        String currentTurn,
        Map<String, Integer> piecesPlaced,
        List<MoveDto> legalMoves,
        String status,
        String drawReason,
        String winner,
        int[] winningLine,
        MoveDto lastMove,
        String playerX,
        String playerO,
        int repetitionLimit,
        int movementPlies,
        int maxMovementPlies,
        boolean aiThinking,
        AiMetaDto aiMeta
    ) {
        public static GameStateMessage fromMatch(GameMatch match) {
            GameEngine snap = match.engineSnapshot();
            Player[] rawBoard = snap.getBoardCopy();
            String[] boardSymbols = new String[BoardTopology.BOARD_SIZE];
            for (int i = 0; i < BoardTopology.BOARD_SIZE; i++) {
                boardSymbols[i] = rawBoard[i] == null ? "" : String.valueOf(rawBoard[i].symbol());
            }

            Map<String, Integer> pieces = new LinkedHashMap<>();
            pieces.put("X", snap.getPieceCount(Player.X));
            pieces.put("O", snap.getPieceCount(Player.O));

            List<MoveDto> legal = snap.getLegalMoves().stream()
                .map(MoveDto::from)
                .toList();

            String winnerSymbol = snap.getWinner().map(p -> String.valueOf(p.symbol())).orElse(null);
            int[] winLine = snap.getWinningLine().orElse(null);
            MoveDto last = snap.getLastMove().map(MoveDto::from).orElse(null);

            AiPrediction aiPred = match.lastAiPrediction().orElse(null);
            AiMetaDto aiMeta = aiPred == null
                ? null
                : new AiMetaDto(aiPred.latencyUs(), aiPred.selection().normalizedLegalProbabilities());

            return new GameStateMessage(
                "GAME_STATE",
                match.matchId(),
                match.mode().name(),
                boardSymbols,
                snap.getCurrentPhase().name(),
                String.valueOf(snap.getCurrentTurn().symbol()),
                pieces,
                legal,
                snap.getStatus().name(),
                snap.getDrawReason().name(),
                winnerSymbol,
                winLine,
                last,
                match.playerXName(),
                match.playerOName(),
                snap.getRepetitionLimit(),
                snap.getMovementPlies(),
                snap.getMaxMovementPlies(),
                match.isAiThinking(),
                aiMeta
            );
        }
    }

    public record OpponentLeftMessage(String type, String matchId, String message) {
        public static OpponentLeftMessage of(String matchId, String message) {
            return new OpponentLeftMessage("OPPONENT_LEFT", matchId, message);
        }
    }

    public record RematchRequestedMessage(String type, String matchId, String byUsername) {
        public static RematchRequestedMessage of(String matchId, String byUsername) {
            return new RematchRequestedMessage("REMATCH_REQUESTED", matchId, byUsername);
        }
    }

    public record MatchLeftMessage(String type, String matchId) {
        public static MatchLeftMessage of(String matchId) {
            return new MatchLeftMessage("MATCH_LEFT", matchId);
        }
    }

    public record ErrorMessage(String type, String code, String message) {
        public static ErrorMessage of(String code, String message) {
            return new ErrorMessage("ERROR", code, message);
        }
    }
}
