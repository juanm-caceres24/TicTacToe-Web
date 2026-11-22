package com.tictactoe.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tictactoe.ai.NeuralNetworkClient;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.engine.Move;
import com.tictactoe.web.ProtocolMessages;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchManagerTest {

    private MatchManager manager;

    private static final class FakeSender implements SessionSender {
        final List<Object> messages = new ArrayList<>();
        boolean open = true;

        @Override
        public synchronized void sendJson(Object message) {
            if (open) {
                messages.add(message);
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        synchronized Object last() {
            return messages.get(messages.size() - 1);
        }

        synchronized void clear() {
            messages.clear();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        ServerConfig config = new ServerConfig(
            "127.0.0.1",
            0,
            10,
            20,
            3,
            50,
            new ServerConfig.PublicIpLookupConfig(false, "https://api.ipify.org", 1000),
            new ServerConfig.AiServerConfig("127.0.0.1", closedPort, 300, 300)
        );
        manager = new MatchManager(config, new NeuralNetworkClient(config.aiServer()));
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void validatesUsernamesStrictlyAgainstXssAndBounds() {
        assertEquals("Alice_01", MatchManager.validateUsername("  Alice_01  ", 20));
        assertEquals("Player One", MatchManager.validateUsername("Player   One", 20));

        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("A", 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("   ", 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("A".repeat(21), 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("<script>x</script>", 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("Bob\"drop", 20));
    }

    @Test
    void onlineMatchmakingPairsFifoDiscardsClosedSessionsAndHandlesRematchAndDisconnect() {
        FakeSender stale = new FakeSender();
        FakeSender alice = new FakeSender();
        FakeSender bob = new FakeSender();

        manager.registerSession("s-stale", stale);
        manager.registerSession("s-alice", alice);
        manager.registerSession("s-bob", bob);

        manager.handleJoinLobby("s-stale", "Ghost");
        manager.handleJoinLobby("s-alice", "Alice");
        manager.handleJoinLobby("s-bob", "Bob");

        // Ghost joins queue, then disconnects silently before Alice joins.
        manager.handleQueueOnline("s-stale");
        assertEquals(1, manager.queueSize());
        stale.open = false;

        // Alice enters queue -> Ghost is pruned, Alice waits at position 1.
        alice.clear();
        manager.handleQueueOnline("s-alice");
        assertEquals(1, manager.queueSize());
        assertInstanceOf(ProtocolMessages.QueueJoinedMessage.class, alice.last());

        // Cancel and re-enqueue works cleanly.
        manager.handleCancelQueue("s-alice");
        assertEquals(0, manager.queueSize());
        assertInstanceOf(ProtocolMessages.QueueCancelledMessage.class, alice.last());

        alice.clear();
        bob.clear();
        manager.handleQueueOnline("s-alice");
        manager.handleQueueOnline("s-bob");

        assertEquals(0, manager.queueSize());
        assertEquals(1, manager.activeMatchCount());
        ProtocolMessages.MatchFoundMessage aliceMatch = assertInstanceOf(
            ProtocolMessages.MatchFoundMessage.class,
            alice.messages.get(1)
        );
        ProtocolMessages.MatchFoundMessage bobMatch = assertInstanceOf(
            ProtocolMessages.MatchFoundMessage.class,
            bob.messages.get(0)
        );
        assertEquals("X", aliceMatch.yourSymbol());
        assertEquals("O", bobMatch.yourSymbol());
        assertEquals("Bob", aliceMatch.opponent());
        assertEquals("Alice", bobMatch.opponent());

        // Out-of-turn move by Bob (O) is rejected.
        bob.clear();
        manager.handleMove("s-bob", Move.placement(4));
        ProtocolMessages.ErrorMessage turnErr = assertInstanceOf(ProtocolMessages.ErrorMessage.class, bob.last());
        assertEquals("INVALID_TURN", turnErr.code());

        // Play quick Phase 1 win for Alice (X: 0, 1, 2; O: 3, 4).
        manager.handleMove("s-alice", Move.placement(0));
        manager.handleMove("s-bob", Move.placement(3));
        manager.handleMove("s-alice", Move.placement(1));
        manager.handleMove("s-bob", Move.placement(4));
        manager.handleMove("s-alice", Move.placement(2));

        ProtocolMessages.GameStateMessage endState = assertInstanceOf(
            ProtocolMessages.GameStateMessage.class,
            alice.last()
        );
        assertEquals("X_WINS", endState.status());
        assertEquals("X", endState.winner());

        // Rematch requires both players; starter alternates to O.
        bob.clear();
        manager.handlePlayAgain("s-alice");
        assertInstanceOf(ProtocolMessages.RematchRequestedMessage.class, bob.last());

        alice.clear();
        bob.clear();
        manager.handlePlayAgain("s-bob");
        ProtocolMessages.GameStateMessage restarted = assertInstanceOf(
            ProtocolMessages.GameStateMessage.class,
            alice.last()
        );
        assertEquals("IN_PROGRESS", restarted.status());
        assertEquals("O", restarted.currentTurn());

        // Bob disconnects -> Alice receives OPPONENT_LEFT and activeMatchCount drops to 0.
        alice.clear();
        manager.unregisterSession("s-bob");
        assertEquals(0, manager.activeMatchCount());
        ProtocolMessages.OpponentLeftMessage leftMsg = assertInstanceOf(
            ProtocolMessages.OpponentLeftMessage.class,
            alice.last()
        );
        assertTrue(leftMsg.message().contains("Bob"));
    }

    @Test
    void localPvpAllowsBothTurnsFromSingleSessionThroughMovementPhase() {
        FakeSender host = new FakeSender();
        manager.registerSession("s-local", host);
        manager.handleJoinLobby("s-local", "Host");

        host.clear();
        manager.handleStartLocalGame("s-local");
        assertEquals(1, manager.activeMatchCount());
        ProtocolMessages.MatchFoundMessage found = assertInstanceOf(
            ProtocolMessages.MatchFoundMessage.class,
            host.messages.get(0)
        );
        assertEquals("LOCAL_PVP", found.mode());
        assertEquals("BOTH", found.yourSymbol());

        // Place 6 pieces without winning -> transition to MOVEMENT.
        manager.handleMove("s-local", Move.placement(0)); // X
        manager.handleMove("s-local", Move.placement(1)); // O
        manager.handleMove("s-local", Move.placement(4)); // X
        manager.handleMove("s-local", Move.placement(2)); // O
        manager.handleMove("s-local", Move.placement(7)); // X
        manager.handleMove("s-local", Move.placement(6)); // O

        ProtocolMessages.GameStateMessage movementState = assertInstanceOf(
            ProtocolMessages.GameStateMessage.class,
            host.last()
        );
        assertEquals("MOVEMENT", movementState.phase());
        assertEquals("X", movementState.currentTurn());

        // Slide X from 7 -> 8 to complete diagonal {0, 4, 8}.
        manager.handleMove("s-local", Move.movement(7, 8));
        ProtocolMessages.GameStateMessage winState = assertInstanceOf(
            ProtocolMessages.GameStateMessage.class,
            host.last()
        );
        assertEquals("X_WINS", winState.status());
    }
}
