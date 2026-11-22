package com.tictactoe.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, framework-free game engine for Mobile Tic-Tac-Toe (3 pieces per player).
 *
 * <p>Enforces all rules:
 * <ul>
 *   <li>Phase 1 ({@link GamePhase#PLACEMENT}): each player places up to 3 pieces on empty cells.</li>
 *   <li>Phase 2 ({@link GamePhase#MOVEMENT}): each player slides one owned piece along a valid edge
 *       defined by {@link BoardTopology} to an adjacent empty cell.</li>
 *   <li>Win detection across all 8 lines (checked before draw conditions).</li>
 *   <li>Cycle prevention via configurable position repetition limit and movement ply limit.</li>
 * </ul>
 */
public final class GameEngine {

    public static final Player DEFAULT_STARTING_PLAYER = Player.X;
    public static final int DEFAULT_REPETITION_LIMIT = 3;
    public static final int DEFAULT_MAX_MOVEMENT_PLIES = 50;

    private final int repetitionLimit;
    private final int maxMovementPlies;

    private final Player[] board = new Player[BoardTopology.BOARD_SIZE];
    private final Map<String, Integer> movementPositionCounts = new HashMap<>();

    private Player currentTurn;
    private GameStatus status;
    private DrawReason drawReason;
    private int[] winningLine;
    private Move lastMove;
    private int xPieces;
    private int oPieces;
    private int totalPlies;
    private int movementPlies;

    /**
     * Creates a new game starting with {@link #DEFAULT_STARTING_PLAYER} and default draw limits.
     */
    public GameEngine() {
        this(DEFAULT_STARTING_PLAYER, DEFAULT_REPETITION_LIMIT, DEFAULT_MAX_MOVEMENT_PLIES);
    }

    /**
     * Creates a new game starting with {@code startingPlayer} and default draw limits.
     */
    public GameEngine(Player startingPlayer) {
        this(startingPlayer, DEFAULT_REPETITION_LIMIT, DEFAULT_MAX_MOVEMENT_PLIES);
    }

    /**
     * Creates a new game with explicit starting player and draw limits.
     *
     * @param startingPlayer   player who makes the first move
     * @param repetitionLimit  number of identical movement-phase positions triggering a draw (must be >= 2)
     * @param maxMovementPlies maximum number of movement-phase plies without a winner (must be >= 1)
     */
    public GameEngine(Player startingPlayer, int repetitionLimit, int maxMovementPlies) {
        Objects.requireNonNull(startingPlayer, "startingPlayer must not be null");
        if (repetitionLimit < 2) {
            throw new IllegalArgumentException("repetitionLimit must be >= 2, got " + repetitionLimit);
        }
        if (maxMovementPlies < 1) {
            throw new IllegalArgumentException("maxMovementPlies must be >= 1, got " + maxMovementPlies);
        }
        this.repetitionLimit = repetitionLimit;
        this.maxMovementPlies = maxMovementPlies;
        initEmpty(startingPlayer);
    }

    /**
     * Reconstructs a {@link GameEngine} from an arbitrary valid board snapshot and side to move.
     */
    public static GameEngine fromState(Player[] board, Player currentTurn) {
        return fromState(board, currentTurn, DEFAULT_REPETITION_LIMIT, DEFAULT_MAX_MOVEMENT_PLIES);
    }

    /**
     * Reconstructs a {@link GameEngine} from an arbitrary valid board snapshot, side to move,
     * and custom draw limits.
     */
    public static GameEngine fromState(
        Player[] board,
        Player currentTurn,
        int repetitionLimit,
        int maxMovementPlies
    ) {
        Objects.requireNonNull(board, "board must not be null");
        Objects.requireNonNull(currentTurn, "currentTurn must not be null");
        if (board.length != BoardTopology.BOARD_SIZE) {
            throw new IllegalArgumentException(
                "Board length must be " + BoardTopology.BOARD_SIZE + ", got " + board.length
            );
        }

        int xCount = 0;
        int oCount = 0;
        for (Player cell : board) {
            if (cell == Player.X) {
                xCount++;
            } else if (cell == Player.O) {
                oCount++;
            }
        }
        if (xCount > BoardTopology.PIECES_PER_PLAYER || oCount > BoardTopology.PIECES_PER_PLAYER) {
            throw new IllegalArgumentException(
                "Each player may have at most " + BoardTopology.PIECES_PER_PLAYER
                    + " pieces, got X=" + xCount + ", O=" + oCount
            );
        }
        if (Math.abs(xCount - oCount) > 1) {
            throw new IllegalArgumentException(
                "Invalid piece count difference between players: X=" + xCount + ", O=" + oCount
            );
        }

        Optional<int[]> xWin = BoardTopology.findWinningLine(board, Player.X);
        Optional<int[]> oWin = BoardTopology.findWinningLine(board, Player.O);
        if (xWin.isPresent() && oWin.isPresent()) {
            throw new IllegalArgumentException("Invalid board state: both players have winning lines");
        }

        GameEngine engine = new GameEngine(currentTurn, repetitionLimit, maxMovementPlies);
        System.arraycopy(board, 0, engine.board, 0, BoardTopology.BOARD_SIZE);
        engine.xPieces = xCount;
        engine.oPieces = oCount;
        engine.totalPlies = xCount + oCount;

        if (xWin.isPresent()) {
            engine.status = GameStatus.X_WINS;
            engine.winningLine = xWin.get();
        } else if (oWin.isPresent()) {
            engine.status = GameStatus.O_WINS;
            engine.winningLine = oWin.get();
        } else if (engine.bothPlayersPlacedAllPieces()) {
            engine.movementPositionCounts.put(engine.encodePositionKey(), 1);
        }

        return engine;
    }

    private void initEmpty(Player startingPlayer) {
        Arrays.fill(board, null);
        movementPositionCounts.clear();
        this.currentTurn = startingPlayer;
        this.status = GameStatus.IN_PROGRESS;
        this.drawReason = DrawReason.NONE;
        this.winningLine = null;
        this.lastMove = null;
        this.xPieces = 0;
        this.oPieces = 0;
        this.totalPlies = 0;
        this.movementPlies = 0;
    }

    /**
     * Resets the game to an empty board starting with {@link #DEFAULT_STARTING_PLAYER}.
     */
    public void reset() {
        reset(DEFAULT_STARTING_PLAYER);
    }

    /**
     * Resets the game to an empty board starting with {@code startingPlayer}.
     */
    public void reset(Player startingPlayer) {
        Objects.requireNonNull(startingPlayer, "startingPlayer must not be null");
        initEmpty(startingPlayer);
    }

    /**
     * Creates an independent deep copy of this engine, including repetition history.
     */
    public GameEngine copy() {
        GameEngine clone = new GameEngine(this.currentTurn, this.repetitionLimit, this.maxMovementPlies);
        System.arraycopy(this.board, 0, clone.board, 0, BoardTopology.BOARD_SIZE);
        clone.movementPositionCounts.putAll(this.movementPositionCounts);
        clone.status = this.status;
        clone.drawReason = this.drawReason;
        clone.winningLine = this.winningLine == null ? null : Arrays.copyOf(this.winningLine, this.winningLine.length);
        clone.lastMove = this.lastMove;
        clone.xPieces = this.xPieces;
        clone.oPieces = this.oPieces;
        clone.totalPlies = this.totalPlies;
        clone.movementPlies = this.movementPlies;
        return clone;
    }

    /**
     * Convenience shortcut to place a piece on {@code to} during {@link GamePhase#PLACEMENT}.
     */
    public void placePiece(int to) {
        applyMove(Move.placement(to));
    }

    /**
     * Convenience shortcut to slide a piece from {@code from} to {@code to} during {@link GamePhase#MOVEMENT}.
     */
    public void movePiece(int from, int to) {
        applyMove(Move.movement(from, to));
    }

    /**
     * Validates and applies a move for the current player.
     *
     * @throws IllegalStateException    if the game is already finished
     * @throws IllegalArgumentException if the move violates phase, occupancy, ownership, or topology rules
     */
    public void applyMove(Move move) {
        Objects.requireNonNull(move, "move must not be null");
        if (status.isTerminal()) {
            throw new IllegalStateException("Cannot apply move: game already ended with status " + status);
        }
        if (!isLegalMove(move)) {
            throw new IllegalArgumentException(
                "Illegal move " + move + " for player " + currentTurn + " in phase " + getCurrentPhase()
            );
        }

        Player mover = currentTurn;
        if (move.isPlacement()) {
            board[move.to()] = mover;
            if (mover == Player.X) {
                xPieces++;
            } else {
                oPieces++;
            }
        } else {
            board[move.from()] = null;
            board[move.to()] = mover;
            movementPlies++;
        }

        totalPlies++;
        lastMove = move;

        Optional<int[]> win = BoardTopology.findWinningLine(board, mover);
        if (win.isPresent()) {
            status = GameStatus.winFor(mover);
            winningLine = win.get();
            drawReason = DrawReason.NONE;
            return;
        }

        currentTurn = mover.opponent();

        if (bothPlayersPlacedAllPieces()) {
            String key = encodePositionKey();
            int occurrences = movementPositionCounts.merge(key, 1, Integer::sum);
            if (occurrences >= repetitionLimit) {
                status = GameStatus.DRAW;
                drawReason = DrawReason.THREEFOLD_REPETITION;
            } else if (movementPlies >= maxMovementPlies) {
                status = GameStatus.DRAW;
                drawReason = DrawReason.MOVE_LIMIT_REACHED;
            }
        }
    }

    /**
     * Returns true if {@code move} is currently legal for {@link #getCurrentTurn()}.
     */
    public boolean isLegalMove(Move move) {
        if (move == null || status.isTerminal()) {
            return false;
        }
        if (getCurrentPhase() == GamePhase.PLACEMENT) {
            return move.isPlacement() && board[move.to()] == null;
        }
        return move.isMovement()
            && board[move.from()] == currentTurn
            && board[move.to()] == null
            && BoardTopology.isAdjacent(move.from(), move.to());
    }

    /**
     * Returns all legal moves for the current player in canonical action order.
     * Returns an empty list if the game has already terminated.
     */
    public List<Move> getLegalMoves() {
        if (status.isTerminal()) {
            return List.of();
        }
        List<Move> legal = new ArrayList<>();
        if (getCurrentPhase() == GamePhase.PLACEMENT) {
            for (int cell = 0; cell < BoardTopology.BOARD_SIZE; cell++) {
                if (board[cell] == null) {
                    legal.add(Move.placement(cell));
                }
            }
        } else {
            for (int from = 0; from < BoardTopology.BOARD_SIZE; from++) {
                if (board[from] == currentTurn) {
                    for (int to : BoardTopology.neighbors(from)) {
                        if (board[to] == null) {
                            legal.add(Move.movement(from, to));
                        }
                    }
                }
            }
        }
        return List.copyOf(legal);
    }

    /**
     * Returns the piece occupying {@code cell}, or {@code null} if empty.
     */
    public Player getCell(int cell) {
        if (!BoardTopology.isValidCell(cell)) {
            throw new IllegalArgumentException("Cell index out of bounds [0..8]: " + cell);
        }
        return board[cell];
    }

    /**
     * Returns a defensive copy of the 9-cell board array (null indicates an empty cell).
     */
    public Player[] getBoardCopy() {
        return Arrays.copyOf(board, board.length);
    }

    public Player getCurrentTurn() {
        return currentTurn;
    }

    /**
     * Returns the active phase for {@link #getCurrentTurn()}.
     */
    public GamePhase getCurrentPhase() {
        return getPieceCount(currentTurn) < BoardTopology.PIECES_PER_PLAYER
            ? GamePhase.PLACEMENT
            : GamePhase.MOVEMENT;
    }

    public int getPieceCount(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        return player == Player.X ? xPieces : oPieces;
    }

    public GameStatus getStatus() {
        return status;
    }

    public DrawReason getDrawReason() {
        return drawReason;
    }

    public Optional<Player> getWinner() {
        return switch (status) {
            case X_WINS -> Optional.of(Player.X);
            case O_WINS -> Optional.of(Player.O);
            default -> Optional.empty();
        };
    }

    public Optional<int[]> getWinningLine() {
        return winningLine == null
            ? Optional.empty()
            : Optional.of(Arrays.copyOf(winningLine, winningLine.length));
    }

    public Optional<Move> getLastMove() {
        return Optional.ofNullable(lastMove);
    }

    public int getTotalPlies() {
        return totalPlies;
    }

    public int getMovementPlies() {
        return movementPlies;
    }

    public int getRepetitionLimit() {
        return repetitionLimit;
    }

    public int getMaxMovementPlies() {
        return maxMovementPlies;
    }

    /**
     * Returns how many times the current position has occurred during the movement phase
     * (0 if both players have not yet placed 3 pieces).
     */
    public int getCurrentPositionOccurrences() {
        if (!bothPlayersPlacedAllPieces()) {
            return 0;
        }
        return movementPositionCounts.getOrDefault(encodePositionKey(), 0);
    }

    /**
     * Returns a compact 10-character key uniquely identifying board occupancy and side to move.
     */
    public String encodePositionKey() {
        char[] chars = new char[BoardTopology.BOARD_SIZE + 1];
        for (int i = 0; i < BoardTopology.BOARD_SIZE; i++) {
            chars[i] = board[i] == null ? '.' : board[i].symbol();
        }
        chars[BoardTopology.BOARD_SIZE] = currentTurn.symbol();
        return new String(chars);
    }

    private boolean bothPlayersPlacedAllPieces() {
        return xPieces == BoardTopology.PIECES_PER_PLAYER
            && oPieces == BoardTopology.PIECES_PER_PLAYER;
    }
}
