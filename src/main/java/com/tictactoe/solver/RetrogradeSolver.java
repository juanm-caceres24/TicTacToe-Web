package com.tictactoe.solver;

import com.tictactoe.engine.BoardTopology;
import com.tictactoe.engine.GameEngine;
import com.tictactoe.engine.GamePhase;
import com.tictactoe.engine.Move;
import com.tictactoe.engine.MoveEncoder;
import com.tictactoe.engine.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Exact cycle-free tablebase solver for Mobile Tic-Tac-Toe (3 pieces per player)
 * using Retrograde Analysis (backward BFS from terminal states).
 *
 * <p>Because Phase 2 (movement) contains cycles, naive recursive Minimax does not terminate cleanly.
 * Retrograde Analysis resolves all reachable player-relative states (both Phase 1 and Phase 2)
 * in milliseconds with exact Distance-To-Mate (DTM):
 * <ul>
 *   <li>From {@code WIN} states, optimal moves force victory in the minimum number of plies.</li>
 *   <li>From {@code DRAW} states, optimal moves keep the position drawn (never allowing an opponent win).</li>
 *   <li>From {@code LOSS} states, optimal moves maximize plies survived before mate.</li>
 * </ul>
 */
public final class RetrogradeSolver {

    /**
     * Total ternary state space size: {@code 3^9 = 19,683}.
     * Digit 0 = empty, digit 1 = current player (+1.0), digit 2 = opponent (-1.0).
     */
    public static final int STATE_SPACE = 19_683;

    private static final int EMPTY_DIGIT = 0;
    private static final int MY_DIGIT = 1;
    private static final int OPP_DIGIT = 2;

    private static final byte OUTCOME_UNKNOWN = 0;
    private static final byte OUTCOME_WIN = 1;
    private static final byte OUTCOME_LOSS = -1;

    private static final int[] POW3 = new int[BoardTopology.BOARD_SIZE];
    private static final int[][] WINNING_LINES = BoardTopology.winningLines();

    static {
        POW3[0] = 1;
        for (int i = 1; i < BoardTopology.BOARD_SIZE; i++) {
            POW3[i] = POW3[i - 1] * 3;
        }
    }

    /**
     * Controls how optimal moves are encoded into the 41-element training target vector.
     */
    public enum TargetMode {
        /**
         * Distributes {@code 1.0 / k} uniformly across all {@code k} equally optimal moves.
         * Recommended for Softmax + CategoricalCrossEntropy training.
         */
        SOFT,

        /**
         * Assigns {@code 1.0} strictly to the first optimal move in canonical action order.
         */
        ONE_HOT
    }

    /**
     * Single supervised training sample ready for CSV export ({@code 9} inputs + {@code 41} targets).
     */
    public record TrainingSample(
        int stateId,
        double[] input,
        double[] target,
        GamePhase phase,
        StateEvaluation.Outcome outcome
    ) {
        public TrainingSample {
            Objects.requireNonNull(input, "input must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(phase, "phase must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            if (input.length != MoveEncoder.INPUT_SIZE) {
                throw new IllegalArgumentException("Expected " + MoveEncoder.INPUT_SIZE + " inputs");
            }
            if (target.length != MoveEncoder.OUTPUT_SIZE) {
                throw new IllegalArgumentException("Expected " + MoveEncoder.OUTPUT_SIZE + " targets");
            }
            input = Arrays.copyOf(input, input.length);
            target = Arrays.copyOf(target, target.length);
        }

        @Override
        public double[] input() {
            return Arrays.copyOf(input, input.length);
        }

        @Override
        public double[] target() {
            return Arrays.copyOf(target, target.length);
        }
    }

    private final boolean[] reachable = new boolean[STATE_SPACE];
    private final boolean[] terminal = new boolean[STATE_SPACE];
    private final byte[] outcome = new byte[STATE_SPACE];
    private final int[] distance = new int[STATE_SPACE];
    private final int[][] forwardNextStates = new int[STATE_SPACE][];
    private final int[][] forwardActionIndices = new int[STATE_SPACE][];

    private final int reachableCount;
    private final int nonTerminalCount;
    private final int winCount;
    private final int drawCount;
    private final int lossCount;

    private RetrogradeSolver() {
        Arrays.fill(distance, -1);

        // Step 1: Forward BFS from empty board (stateId = 0) to discover all reachable states & edges.
        int[] bfsQueue = new int[STATE_SPACE];
        int head = 0;
        int tail = 0;

        reachable[0] = true;
        bfsQueue[tail++] = 0;

        int totalEdges = 0;
        int[] cells = new int[BoardTopology.BOARD_SIZE];
        int[] nextBuf = new int[BoardTopology.TOTAL_ACTION_COUNT];
        int[] actBuf = new int[BoardTopology.TOTAL_ACTION_COUNT];

        while (head < tail) {
            int state = bfsQueue[head++];
            decodeDigits(state, cells);

            if (hasThreeInARow(cells, OPP_DIGIT)) {
                // Opponent completed 3-in-a-row on the previous ply -> current side to move has lost.
                terminal[state] = true;
                forwardNextStates[state] = new int[0];
                forwardActionIndices[state] = new int[0];
                continue;
            }

            int myCount = 0;
            for (int c : cells) {
                if (c == MY_DIGIT) {
                    myCount++;
                }
            }

            int edgeCount = 0;
            if (myCount < BoardTopology.PIECES_PER_PLAYER) {
                // Phase 1: Placement on any empty cell (action indices 0..8)
                for (int to = 0; to < BoardTopology.BOARD_SIZE; to++) {
                    if (cells[to] == EMPTY_DIGIT) {
                        cells[to] = MY_DIGIT;
                        int nextState = encodeFlipped(cells);
                        cells[to] = EMPTY_DIGIT;

                        nextBuf[edgeCount] = nextState;
                        actBuf[edgeCount] = to;
                        edgeCount++;

                        if (!reachable[nextState]) {
                            reachable[nextState] = true;
                            bfsQueue[tail++] = nextState;
                        }
                    }
                }
            } else {
                // Phase 2: Movement along BoardTopology edges (action indices 9..40)
                for (int from = 0; from < BoardTopology.BOARD_SIZE; from++) {
                    if (cells[from] != MY_DIGIT) {
                        continue;
                    }
                    for (int to : BoardTopology.neighbors(from)) {
                        if (cells[to] == EMPTY_DIGIT) {
                            cells[from] = EMPTY_DIGIT;
                            cells[to] = MY_DIGIT;
                            int nextState = encodeFlipped(cells);
                            cells[to] = EMPTY_DIGIT;
                            cells[from] = MY_DIGIT;

                            nextBuf[edgeCount] = nextState;
                            actBuf[edgeCount] = BoardTopology.moveToActionIndex(Move.movement(from, to));
                            edgeCount++;

                            if (!reachable[nextState]) {
                                reachable[nextState] = true;
                                bfsQueue[tail++] = nextState;
                            }
                        }
                    }
                }
            }

            forwardNextStates[state] = Arrays.copyOf(nextBuf, edgeCount);
            forwardActionIndices[state] = Arrays.copyOf(actBuf, edgeCount);
            totalEdges += edgeCount;
        }

        // Step 2: Build flat reverse adjacency list (predecessors) and remaining-unknown out-degrees.
        int[] revHead = new int[STATE_SPACE];
        Arrays.fill(revHead, -1);
        int[] revFrom = new int[totalEdges];
        int[] revNext = new int[totalEdges];
        int[] remainingUnknown = new int[STATE_SPACE];

        int edgeCursor = 0;
        int retroHead = 0;
        int retroTail = 0;
        int[] retroQueue = new int[STATE_SPACE];

        for (int i = 0; i < tail; i++) {
            int u = bfsQueue[i];
            if (terminal[u]) {
                outcome[u] = OUTCOME_LOSS;
                distance[u] = 0;
                retroQueue[retroTail++] = u;
                continue;
            }
            int[] nexts = forwardNextStates[u];
            remainingUnknown[u] = nexts.length;
            for (int v : nexts) {
                revFrom[edgeCursor] = u;
                revNext[edgeCursor] = revHead[v];
                revHead[v] = edgeCursor;
                edgeCursor++;
            }
        }

        // Step 3: Backward BFS (Retrograde Analysis) propagating WIN/LOSS in non-decreasing DTM order.
        while (retroHead < retroTail) {
            int v = retroQueue[retroHead++];
            byte vOutcome = outcome[v];
            int vDist = distance[v];

            if (vOutcome == OUTCOME_LOSS) {
                // Any predecessor that can move into a LOSS state immediately secures a WIN.
                for (int e = revHead[v]; e != -1; e = revNext[e]) {
                    int u = revFrom[e];
                    if (outcome[u] == OUTCOME_UNKNOWN) {
                        outcome[u] = OUTCOME_WIN;
                        distance[u] = vDist + 1;
                        retroQueue[retroTail++] = u;
                    }
                }
            } else if (vOutcome == OUTCOME_WIN) {
                // A predecessor becomes LOSS only once ALL its legal moves lead to opponent WIN states.
                for (int e = revHead[v]; e != -1; e = revNext[e]) {
                    int u = revFrom[e];
                    if (outcome[u] == OUTCOME_UNKNOWN) {
                        remainingUnknown[u]--;
                        if (remainingUnknown[u] == 0) {
                            outcome[u] = OUTCOME_LOSS;
                            distance[u] = vDist + 1;
                            retroQueue[retroTail++] = u;
                        }
                    }
                }
            }
        }

        // Step 4: Tally summary statistics over reachable states.
        int nonTerm = 0;
        int wins = 0;
        int draws = 0;
        int losses = 0;
        for (int i = 0; i < tail; i++) {
            int s = bfsQueue[i];
            if (terminal[s]) {
                continue;
            }
            nonTerm++;
            if (outcome[s] == OUTCOME_WIN) {
                wins++;
            } else if (outcome[s] == OUTCOME_LOSS) {
                losses++;
            } else {
                draws++;
            }
        }

        this.reachableCount = tail;
        this.nonTerminalCount = nonTerm;
        this.winCount = wins;
        this.drawCount = draws;
        this.lossCount = losses;
    }

    /**
     * Builds and solves the complete Mobile Tic-Tac-Toe tablebase.
     */
    public static RetrogradeSolver solve() {
        return new RetrogradeSolver();
    }

    /**
     * Evaluates the current position of {@code engine} from the perspective of {@link GameEngine#getCurrentTurn()}.
     */
    public StateEvaluation evaluate(GameEngine engine) {
        Objects.requireNonNull(engine, "engine must not be null");
        return evaluate(engine.getBoardCopy(), engine.getCurrentTurn());
    }

    /**
     * Evaluates an arbitrary board state from the perspective of {@code sideToMove}.
     *
     * @throws IllegalArgumentException if the position is not reachable under Mobile Tic-Tac-Toe rules
     */
    public StateEvaluation evaluate(Player[] board, Player sideToMove) {
        int stateId = encodeStateId(board, sideToMove);
        return evaluateStateId(stateId);
    }

    /**
     * Evaluates a pre-encoded player-relative state ID {@code 0..19682}.
     */
    public StateEvaluation evaluateStateId(int stateId) {
        if (stateId < 0 || stateId >= STATE_SPACE || !reachable[stateId]) {
            throw new IllegalArgumentException("State ID is not reachable in Mobile Tic-Tac-Toe: " + stateId);
        }

        StateEvaluation.Outcome result = toOutcome(outcome[stateId]);
        if (terminal[stateId]) {
            return new StateEvaluation(result, 0, List.of());
        }

        List<Move> bestMoves = computeOptimalMoves(stateId);
        return new StateEvaluation(result, distance[stateId], bestMoves);
    }

    /**
     * Builds supervised training samples for every reachable non-terminal state (both Phase 1 and Phase 2).
     */
    public List<TrainingSample> buildTrainingSamples(TargetMode targetMode) {
        Objects.requireNonNull(targetMode, "targetMode must not be null");
        List<TrainingSample> samples = new ArrayList<>(nonTerminalCount);
        int[] cells = new int[BoardTopology.BOARD_SIZE];

        for (int stateId = 0; stateId < STATE_SPACE; stateId++) {
            if (!reachable[stateId] || terminal[stateId]) {
                continue;
            }
            decodeDigits(stateId, cells);

            double[] input = new double[MoveEncoder.INPUT_SIZE];
            int myCount = 0;
            for (int i = 0; i < BoardTopology.BOARD_SIZE; i++) {
                if (cells[i] == MY_DIGIT) {
                    input[i] = 1.0;
                    myCount++;
                } else if (cells[i] == OPP_DIGIT) {
                    input[i] = -1.0;
                } else {
                    input[i] = 0.0;
                }
            }

            List<Move> optimalMoves = computeOptimalMoves(stateId);
            double[] target = new double[MoveEncoder.OUTPUT_SIZE];
            if (targetMode == TargetMode.ONE_HOT) {
                int idx = BoardTopology.moveToActionIndex(optimalMoves.get(0));
                target[idx] = 1.0;
            } else {
                double weight = 1.0 / optimalMoves.size();
                for (Move m : optimalMoves) {
                    target[BoardTopology.moveToActionIndex(m)] = weight;
                }
            }

            GamePhase phase = myCount < BoardTopology.PIECES_PER_PLAYER
                ? GamePhase.PLACEMENT
                : GamePhase.MOVEMENT;
            samples.add(new TrainingSample(stateId, input, target, phase, toOutcome(outcome[stateId])));
        }

        return samples;
    }

    /**
     * Encodes a board array into a base-3 player-relative state ID ({@code 0..19682}).
     */
    public static int encodeStateId(Player[] board, Player sideToMove) {
        Objects.requireNonNull(board, "board must not be null");
        Objects.requireNonNull(sideToMove, "sideToMove must not be null");
        if (board.length != BoardTopology.BOARD_SIZE) {
            throw new IllegalArgumentException("Board length must be " + BoardTopology.BOARD_SIZE);
        }
        int id = 0;
        for (int i = 0; i < BoardTopology.BOARD_SIZE; i++) {
            int digit;
            if (board[i] == null) {
                digit = EMPTY_DIGIT;
            } else if (board[i] == sideToMove) {
                digit = MY_DIGIT;
            } else {
                digit = OPP_DIGIT;
            }
            id += digit * POW3[i];
        }
        return id;
    }

    public int reachableStateCount() {
        return reachableCount;
    }

    public int nonTerminalStateCount() {
        return nonTerminalCount;
    }

    public int winStateCount() {
        return winCount;
    }

    public int drawStateCount() {
        return drawCount;
    }

    public int lossStateCount() {
        return lossCount;
    }

    private List<Move> computeOptimalMoves(int stateId) {
        int[] nexts = forwardNextStates[stateId];
        int[] actions = forwardActionIndices[stateId];
        byte stateOutcome = outcome[stateId];
        List<Move> optimal = new ArrayList<>();

        if (stateOutcome == OUTCOME_WIN) {
            // Choose moves leading to opponent LOSS with minimum distance to mate.
            int minOppDist = Integer.MAX_VALUE;
            for (int v : nexts) {
                if (outcome[v] == OUTCOME_LOSS && distance[v] < minOppDist) {
                    minOppDist = distance[v];
                }
            }
            for (int i = 0; i < nexts.length; i++) {
                int v = nexts[i];
                if (outcome[v] == OUTCOME_LOSS && distance[v] == minOppDist) {
                    optimal.add(BoardTopology.actionIndexToMove(actions[i]));
                }
            }
        } else if (stateOutcome == OUTCOME_UNKNOWN) {
            // Drawn position: choose moves that maintain a draw (opponent cannot force a win).
            for (int i = 0; i < nexts.length; i++) {
                if (outcome[nexts[i]] == OUTCOME_UNKNOWN) {
                    optimal.add(BoardTopology.actionIndexToMove(actions[i]));
                }
            }
        } else {
            // Losing position: all moves lead to opponent WIN; maximize plies survived.
            int maxOppDist = -1;
            for (int v : nexts) {
                if (distance[v] > maxOppDist) {
                    maxOppDist = distance[v];
                }
            }
            for (int i = 0; i < nexts.length; i++) {
                if (distance[nexts[i]] == maxOppDist) {
                    optimal.add(BoardTopology.actionIndexToMove(actions[i]));
                }
            }
        }

        return optimal;
    }

    private static StateEvaluation.Outcome toOutcome(byte code) {
        if (code == OUTCOME_WIN) {
            return StateEvaluation.Outcome.WIN;
        }
        if (code == OUTCOME_LOSS) {
            return StateEvaluation.Outcome.LOSS;
        }
        return StateEvaluation.Outcome.DRAW;
    }

    private static void decodeDigits(int stateId, int[] outCells) {
        int value = stateId;
        for (int i = 0; i < BoardTopology.BOARD_SIZE; i++) {
            outCells[i] = value % 3;
            value /= 3;
        }
    }

    private static int encodeFlipped(int[] cells) {
        int id = 0;
        for (int i = 0; i < BoardTopology.BOARD_SIZE; i++) {
            int digit = cells[i];
            int flipped;
            if (digit == MY_DIGIT) {
                flipped = OPP_DIGIT;
            } else if (digit == OPP_DIGIT) {
                flipped = MY_DIGIT;
            } else {
                flipped = EMPTY_DIGIT;
            }
            id += flipped * POW3[i];
        }
        return id;
    }

    private static boolean hasThreeInARow(int[] cells, int digit) {
        for (int[] line : WINNING_LINES) {
            if (cells[line[0]] == digit && cells[line[1]] == digit && cells[line[2]] == digit) {
                return true;
            }
        }
        return false;
    }
}
