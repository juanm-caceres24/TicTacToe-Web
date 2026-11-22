package com.tictactoe.solver;

import com.tictactoe.engine.Move;
import java.util.List;
import java.util.Objects;

/**
 * Exact game-theoretic evaluation of a reachable Mobile Tic-Tac-Toe state
 * from the perspective of the player to move.
 *
 * @param outcome        theoretical result under optimal play ({@link Outcome#WIN}, {@link Outcome#DRAW}, or {@link Outcome#LOSS})
 * @param distanceToMate plies to forced checkmate ({@code 0} for terminal lost states, {@code -1} for draws)
 * @param optimalMoves   all legally optimal moves in canonical action order (empty for terminal states)
 */
public record StateEvaluation(
    Outcome outcome,
    int distanceToMate,
    List<Move> optimalMoves
) {

    /**
     * Game-theoretic outcome from the perspective of the side to move.
     */
    public enum Outcome {
        WIN,
        DRAW,
        LOSS
    }

    public StateEvaluation {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(optimalMoves, "optimalMoves must not be null");
        optimalMoves = List.copyOf(optimalMoves);
    }
}
