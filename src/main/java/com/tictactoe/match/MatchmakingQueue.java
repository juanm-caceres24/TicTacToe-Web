package com.tictactoe.match;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe FIFO matchmaking queue for pairing online PvP players.
 * Automatically discards stale disconnected sessions before pairing.
 */
public final class MatchmakingQueue {

    /**
     * Two matched sessions ready to start an {@link MatchMode#ONLINE_PVP} match.
     */
    public record PairedPlayers(PlayerSession first, PlayerSession second) {
        public PairedPlayers {
            Objects.requireNonNull(first, "first must not be null");
            Objects.requireNonNull(second, "second must not be null");
        }
    }

    private final Deque<PlayerSession> waiting = new ArrayDeque<>();

    /**
     * Enqueues {@code session} or pairs it immediately with the longest-waiting open opponent.
     *
     * @return a {@link PairedPlayers} pair if an opponent was waiting, or {@link Optional#empty()} if queued.
     */
    public synchronized Optional<PairedPlayers> enqueueOrPair(PlayerSession session) {
        Objects.requireNonNull(session, "session must not be null");
        pruneDisconnected();

        if (contains(session)) {
            return Optional.empty();
        }

        while (!waiting.isEmpty()) {
            PlayerSession opponent = waiting.pollFirst();
            if (opponent == null || opponent.sessionId().equals(session.sessionId())) {
                continue;
            }
            if (!opponent.sender().isOpen()) {
                opponent.setInQueue(false);
                continue;
            }
            opponent.setInQueue(false);
            session.setInQueue(false);
            return Optional.of(new PairedPlayers(opponent, session));
        }

        waiting.addLast(session);
        session.setInQueue(true);
        return Optional.empty();
    }

    /**
     * Removes {@code session} from the waiting queue if present.
     *
     * @return true if the session was removed
     */
    public synchronized boolean remove(PlayerSession session) {
        if (session == null) {
            return false;
        }
        boolean removed = waiting.removeIf(s -> s.sessionId().equals(session.sessionId()));
        session.setInQueue(false);
        return removed;
    }

    /**
     * Returns the 1-based queue position of {@code session}, or {@code -1} if not queued.
     */
    public synchronized int positionOf(PlayerSession session) {
        if (session == null) {
            return -1;
        }
        int pos = 1;
        for (PlayerSession s : waiting) {
            if (s.sessionId().equals(session.sessionId())) {
                return pos;
            }
            pos++;
        }
        return -1;
    }

    /**
     * Returns the current number of active open sessions waiting in the queue.
     */
    public synchronized int size() {
        pruneDisconnected();
        return waiting.size();
    }

    private boolean contains(PlayerSession session) {
        for (PlayerSession s : waiting) {
            if (s.sessionId().equals(session.sessionId())) {
                return true;
            }
        }
        return false;
    }

    private void pruneDisconnected() {
        Iterator<PlayerSession> it = waiting.iterator();
        while (it.hasNext()) {
            PlayerSession s = it.next();
            if (!s.sender().isOpen()) {
                s.setInQueue(false);
                it.remove();
            }
        }
    }
}
