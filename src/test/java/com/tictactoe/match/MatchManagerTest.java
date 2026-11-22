package com.tictactoe.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tictactoe.ai.NeuralNetworkClient;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.engine.Move;
import com.tictactoe.engine.Player;
import com.tictactoe.web.ProtocolMessages;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MatchManager manager;
    private HttpServer mockAi;
    private int closedPort;

    private static final class FakeSender implements SessionSender {
        final List<Object> messages = new ArrayList<>();
        volatile boolean open = true;

        @Override
        public synchronized void sendJson(Object message) {
            if (open) {
                messages.add(message);
                notifyAll();
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        synchronized Object last() {
            return messages.get(messages.size() - 1);
        }

        synchronized <T> T awaitMessage(Class<T> type, java.util.function.Predicate<T> predicate)
            throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Object m = messages.get(i);
                    if (type.isInstance(m)) {
                        T typed = type.cast(m);
                        if (predicate.test(typed)) {
                            return typed;
                        }
                    }
                }
                long remainingMs = Math.max(5, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                wait(Math.min(remainingMs, 100));
            }
            throw new AssertionError("Timed out waiting for " + type.getSimpleName() + "; got=" + messages);
        }

        synchronized void clear() {
            messages.clear();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        manager = createManager(10, 2, 20, 3, 50, closedPort);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
            manager = null;
        }
        if (mockAi != null) {
            mockAi.stop(0);
            mockAi = null;
        }
    }

    private MatchManager createManager(
        int maxMatches,
        int minUser,
        int maxUser,
        int repLimit,
        int maxPlies,
        int aiPort
    ) {
        ServerConfig config = new ServerConfig(
            "127.0.0.1",
            0,
            maxMatches,
            minUser,
            maxUser,
            repLimit,
            maxPlies,
            ServerConfig.TransportConfig.defaults(),
            new ServerConfig.PublicIpLookupConfig(false, "https://api.ipify.org", 1000),
            new ServerConfig.AiServerConfig("127.0.0.1", aiPort, 500, 1500, 4)
        );
        return new MatchManager(config, new NeuralNetworkClient(config.aiServer()));
    }

    @Test
    void validatesUsernamesStrictlyAgainstXssAndCustomBounds() {
        assertEquals("Alice_01", MatchManager.validateUsername("  Alice_01  ", 2, 20));
        assertEquals("Player One", MatchManager.validateUsername("Player   One", 2, 20));
        assertEquals("A", MatchManager.validateUsername("A", 1, 10));

        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername(null, 2, 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("A", 2, 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("AB", 3, 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("   ", 2, 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("A".repeat(21), 2, 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("<script>x</script>", 2, 20));
        assertThrows(IllegalArgumentException.class, () -> MatchManager.validateUsername("Bob\"drop", 2, 20));
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

        // Mid-game online rematch is rejected until match ends.
        alice.clear();
        manager.handlePlayAgain("s-alice");
        ProtocolMessages.ErrorMessage earlyRematch = assertInstanceOf(ProtocolMessages.ErrorMessage.class, alice.last());
        assertEquals("REMATCH_ERROR", earlyRematch.code());

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
        assertEquals(3, endState.repetitionLimit());

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
    void queuePreventsDuplicateSelfPairingAndPreservesFifoForThreePlayers() {
        FakeSender a = new FakeSender();
        FakeSender b = new FakeSender();
        FakeSender c = new FakeSender();
        manager.registerSession("a", a);
        manager.registerSession("b", b);
        manager.registerSession("c", c);
        manager.handleJoinLobby("a", "Alice");
        manager.handleJoinLobby("b", "Bob");
        manager.handleJoinLobby("c", "Charlie");

        // Enqueueing Alice twice must not duplicate or self-pair.
        manager.handleQueueOnline("a");
        manager.handleQueueOnline("a");
        assertEquals(1, manager.queueSize());
        assertEquals(0, manager.activeMatchCount());

        // Bob pairs with Alice; Charlie enters queue at position 1.
        a.clear();
        b.clear();
        c.clear();
        manager.handleQueueOnline("b");
        manager.handleQueueOnline("c");

        assertEquals(1, manager.activeMatchCount());
        assertEquals(1, manager.queueSize());
        ProtocolMessages.MatchFoundMessage bMatch = assertInstanceOf(
            ProtocolMessages.MatchFoundMessage.class,
            b.messages.get(0)
        );
        assertEquals("Alice", bMatch.opponent());
        ProtocolMessages.QueueJoinedMessage cQueued = assertInstanceOf(
            ProtocolMessages.QueueJoinedMessage.class,
            c.last()
        );
        assertEquals(1, cQueued.position());
    }

    @Test
    void enforcesMaxConcurrentMatchesAcrossAllModes() throws IOException {
        int aiPort = startControlledAi(new AtomicBoolean(true), new AtomicBoolean(false), null, null);
        manager.close();
        manager = createManager(1, 2, 20, 3, 50, aiPort);

        FakeSender p1 = new FakeSender();
        FakeSender p2 = new FakeSender();
        manager.registerSession("p1", p1);
        manager.registerSession("p2", p2);
        manager.handleJoinLobby("p1", "One");
        manager.handleJoinLobby("p2", "Two");

        // Occupy the single match slot with Local PvP.
        manager.handleStartLocalGame("p1");
        assertEquals(1, manager.activeMatchCount());

        // p2 is rejected with SERVER_FULL across all 3 modes.
        p2.clear();
        manager.handleQueueOnline("p2");
        assertEquals("SERVER_FULL", assertInstanceOf(ProtocolMessages.ErrorMessage.class, p2.last()).code());

        p2.clear();
        manager.handleStartAiGame("p2", Player.X, false);
        assertEquals("SERVER_FULL", assertInstanceOf(ProtocolMessages.ErrorMessage.class, p2.last()).code());

        p2.clear();
        manager.handleStartLocalGame("p2");
        assertEquals("SERVER_FULL", assertInstanceOf(ProtocolMessages.ErrorMessage.class, p2.last()).code());
    }

    @Test
    void rejectsUnauthenticatedOutsideMatchAndClosedMatchActions() {
        FakeSender s = new FakeSender();
        manager.registerSession("anon", s);

        manager.handleQueueOnline("anon");
        assertEquals("NOT_JOINED", assertInstanceOf(ProtocolMessages.ErrorMessage.class, s.last()).code());

        manager.handleStartLocalGame("anon");
        assertEquals("NOT_JOINED", assertInstanceOf(ProtocolMessages.ErrorMessage.class, s.last()).code());

        manager.handleJoinLobby("anon", "User1");
        s.clear();
        manager.handleMove("anon", Move.placement(0));
        assertEquals("NO_ACTIVE_MATCH", assertInstanceOf(ProtocolMessages.ErrorMessage.class, s.last()).code());

        s.clear();
        manager.handlePlayAgain("anon");
        assertEquals("NO_ACTIVE_MATCH", assertInstanceOf(ProtocolMessages.ErrorMessage.class, s.last()).code());

        s.clear();
        manager.handleRetryAiMove("anon");
        assertEquals("NO_ACTIVE_MATCH", assertInstanceOf(ProtocolMessages.ErrorMessage.class, s.last()).code());
    }

    @Test
    void explicitLeaveAndModeSwitchNotifyOnlineOpponentAndFreeMatch() {
        FakeSender alice = new FakeSender();
        FakeSender bob = new FakeSender();
        manager.registerSession("a", alice);
        manager.registerSession("b", bob);
        manager.handleJoinLobby("a", "Alice");
        manager.handleJoinLobby("b", "Bob");

        manager.handleQueueOnline("a");
        manager.handleQueueOnline("b");
        assertEquals(1, manager.activeMatchCount());

        // Explicit leave confirms MATCH_LEFT to leaver and OPPONENT_LEFT to opponent.
        alice.clear();
        bob.clear();
        manager.handleLeaveMatch("a");
        assertEquals(0, manager.activeMatchCount());
        assertInstanceOf(ProtocolMessages.MatchLeftMessage.class, alice.last());
        assertInstanceOf(ProtocolMessages.OpponentLeftMessage.class, bob.last());

        // Re-pair, then Alice switches straight into LOCAL_PVP -> Bob receives OPPONENT_LEFT.
        manager.handleQueueOnline("a");
        manager.handleQueueOnline("b");
        bob.clear();
        manager.handleStartLocalGame("a");
        assertEquals(1, manager.activeMatchCount());
        assertInstanceOf(ProtocolMessages.OpponentLeftMessage.class, bob.last());
    }

    @Test
    void vsAiSupportsHumanAsOBlockingDuringThinkingRetryAfterFailureAndInFlightClose() throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(true);
        AtomicBoolean failPredict = new AtomicBoolean(false);
        AtomicReference<CountDownLatch> enteredPredict = new AtomicReference<>();
        AtomicReference<CountDownLatch> releasePredict = new AtomicReference<>();
        int aiPort = startControlledAi(healthy, failPredict, enteredPredict, releasePredict);

        manager.close();
        manager = createManager(10, 2, 20, 3, 50, aiPort);

        FakeSender human = new FakeSender();
        manager.registerSession("h", human);
        manager.handleJoinLobby("h", "Human");

        // 1. Human plays as O, AI moves first (X) with an in-flight pause so we test aiThinking locks.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        enteredPredict.set(entered);
        releasePredict.set(release);

        human.clear();
        manager.handleStartAiGame("h", Player.O, true);
        assertTrue(entered.await(3, TimeUnit.SECONDS));

        // While AI is thinking, human move and PLAY_AGAIN must be rejected cleanly.
        manager.handleMove("h", Move.placement(0));
        ProtocolMessages.ErrorMessage moveBusy = human.awaitMessage(
            ProtocolMessages.ErrorMessage.class,
            e -> "INVALID_TURN".equals(e.code())
        );
        assertTrue(moveBusy.message().toLowerCase().contains("ai"));

        manager.handlePlayAgain("h");
        human.awaitMessage(ProtocolMessages.ErrorMessage.class, e -> "REMATCH_ERROR".equals(e.code()));

        // Release AI opening move -> AI (X) places at center 4, now human (O) to move.
        release.countDown();
        enteredPredict.set(null);
        releasePredict.set(null);

        ProtocolMessages.GameStateMessage afterAi = human.awaitMessage(
            ProtocolMessages.GameStateMessage.class,
            gs -> !gs.aiThinking() && "O".equals(gs.currentTurn())
        );
        assertEquals("X", afterAi.board()[4]);

        // RETRY_AI_MOVE on human's turn is rejected.
        human.clear();
        manager.handleRetryAiMove("h");
        assertEquals("INVALID_TURN", assertInstanceOf(ProtocolMessages.ErrorMessage.class, human.last()).code());

        // 2. Make next /predict fail -> AI_ERROR + aiThinking=false, then RETRY_AI_MOVE recovers.
        failPredict.set(true);
        human.clear();
        manager.handleMove("h", Move.placement(0));
        human.awaitMessage(ProtocolMessages.ErrorMessage.class, e -> "AI_ERROR".equals(e.code()));
        ProtocolMessages.GameStateMessage stuckState = human.awaitMessage(
            ProtocolMessages.GameStateMessage.class,
            gs -> !gs.aiThinking() && "X".equals(gs.currentTurn())
        );
        assertEquals("O", stuckState.board()[0]);

        failPredict.set(false);
        human.clear();
        manager.handleRetryAiMove("h");
        ProtocolMessages.GameStateMessage recovered = human.awaitMessage(
            ProtocolMessages.GameStateMessage.class,
            gs -> !gs.aiThinking() && "O".equals(gs.currentTurn()) && gs.piecesPlaced().get("X") == 2
        );
        assertEquals("O", recovered.currentTurn());

        // 3. PLAY_AGAIN when AI goes offline -> AI_UNAVAILABLE without crashing.
        healthy.set(false);
        human.clear();
        manager.handlePlayAgain("h");
        assertEquals("AI_UNAVAILABLE", assertInstanceOf(ProtocolMessages.ErrorMessage.class, human.last()).code());

        // 4. Abandoning match while an AI HTTP call is in flight does not crash or emit post-close state.
        healthy.set(true);
        CountDownLatch entered2 = new CountDownLatch(1);
        CountDownLatch release2 = new CountDownLatch(1);
        enteredPredict.set(entered2);
        releasePredict.set(release2);
        manager.handleMove("h", Move.placement(1));
        assertTrue(entered2.await(3, TimeUnit.SECONDS));

        human.clear();
        manager.handleLeaveMatch("h");
        assertEquals(0, manager.activeMatchCount());
        release2.countDown();
        Thread.sleep(60);
        // Only MATCH_LEFT should have been sent, no post-close GAME_STATE.
        assertEquals(1, human.messages.size());
        assertInstanceOf(ProtocolMessages.MatchLeftMessage.class, human.last());
    }

    @Test
    void customRepetitionLimitAndMaxMovementPliesPropagateToDraws() {
        manager.close();
        // repetitionLimit=2 -> returning once to an already-seen 3v3 state draws immediately.
        manager = createManager(10, 2, 20, 2, 50, closedPort);
        FakeSender host = new FakeSender();
        manager.registerSession("s", host);
        manager.handleJoinLobby("s", "Host");
        manager.handleStartLocalGame("s");

        // Reach non-winning 3v3: X={0,4,7}, O={2,6,8}.
        manager.handleMove("s", Move.placement(0));
        manager.handleMove("s", Move.placement(2));
        manager.handleMove("s", Move.placement(4));
        manager.handleMove("s", Move.placement(6));
        manager.handleMove("s", Move.placement(7));
        manager.handleMove("s", Move.placement(8));

        // Cycle once back to initial 3v3 position (occurrence 2 == repetitionLimit -> DRAW).
        manager.handleMove("s", Move.movement(0, 3));
        manager.handleMove("s", Move.movement(2, 5));
        manager.handleMove("s", Move.movement(3, 0));
        manager.handleMove("s", Move.movement(5, 2));

        ProtocolMessages.GameStateMessage draw = assertInstanceOf(ProtocolMessages.GameStateMessage.class, host.last());
        assertEquals("DRAW", draw.status());
        assertEquals("THREEFOLD_REPETITION", draw.drawReason());
        assertEquals(2, draw.repetitionLimit());
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

    @Test
    void concurrentMultiThreadedQueueMovesAndDisconnectsLeaveCleanState() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        int totalClients = 24;
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalClients; i++) {
            final int id = i;
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    String sid = "c-" + id;
                    FakeSender sender = new FakeSender();
                    manager.registerSession(sid, sender);
                    manager.handleJoinLobby(sid, "Player_" + id);
                    manager.handleQueueOnline(sid);
                    manager.handleMove(sid, Move.placement(id % 9));
                    manager.handleLeaveMatch(sid);
                    manager.unregisterSession(sid);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(0, manager.connectedSessionCount());
        assertEquals(0, manager.queueSize());
        assertEquals(0, manager.activeMatchCount());
        assertFalse(manager.activeMatchCount() < 0);
    }

    private int startControlledAi(
        AtomicBoolean healthy,
        AtomicBoolean failPredict,
        AtomicReference<CountDownLatch> enteredPredict,
        AtomicReference<CountDownLatch> releasePredict
    ) throws IOException {
        mockAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockAi.setExecutor(Executors.newCachedThreadPool());
        mockAi.createContext("/health", ex -> {
            if (healthy.get()) {
                sendJson(ex, 200, "{\"status\":\"ok\"}");
            } else {
                sendJson(ex, 503, "{\"status\":\"down\"}");
            }
        });
        mockAi.createContext("/info", ex -> sendJson(
            ex,
            200,
            "{\"input_size\":9,\"output_size\":41,\"num_layers\":3,\"parameters\":23081}"
        ));
        mockAi.createContext("/predict", ex -> {
            if (enteredPredict != null && enteredPredict.get() != null) {
                enteredPredict.get().countDown();
            }
            if (releasePredict != null && releasePredict.get() != null) {
                try {
                    releasePredict.get().await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failPredict.get()) {
                sendJson(ex, 500, "{\"error\":\"transient inference failure\"}");
                return;
            }
            double[] scores = new double[41];
            scores[4] = 0.90;
            scores[2] = 0.80;
            scores[8] = 0.70;
            for (int i = 0; i < 41; i++) {
                if (scores[i] == 0.0) {
                    scores[i] = 0.01 * (i + 1) / 41.0;
                }
            }
            sendJson(ex, 200, "{\"output\":" + MAPPER.writeValueAsString(scores) + ",\"latency_us\":11}");
        });
        mockAi.start();
        return mockAi.getAddress().getPort();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
