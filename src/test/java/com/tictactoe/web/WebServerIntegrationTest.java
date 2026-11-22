package com.tictactoe.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tictactoe.config.BuildInfo;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.engine.BoardTopology;
import com.tictactoe.engine.Move;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebServerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer mockAiServer;
    private WebServer webServer;

    @AfterEach
    void tearDown() {
        if (webServer != null) {
            webServer.close();
            webServer = null;
        }
        if (mockAiServer != null) {
            mockAiServer.stop(0);
            mockAiServer = null;
        }
    }

    private static final class TestWsClient implements AutoCloseable {
        private final WebSocket ws;
        private final BlockingQueue<JsonNode> inbox;
        private final CountDownLatch closedLatch;

        private TestWsClient(WebSocket ws, BlockingQueue<JsonNode> inbox, CountDownLatch closedLatch) {
            this.ws = ws;
            this.inbox = inbox;
            this.closedLatch = closedLatch;
        }

        static TestWsClient connect(int port) throws Exception {
            BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
            CountDownLatch closed = new CountDownLatch(1);
            StringBuilder buffer = new StringBuilder();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            String complete = buffer.toString();
                            buffer.setLength(0);
                            try {
                                queue.add(MAPPER.readTree(complete));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closed.countDown();
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        closed.countDown();
                    }
                })
                .get(5, TimeUnit.SECONDS);

            return new TestWsClient(ws, queue, closed);
        }

        void send(String json) throws Exception {
            ws.sendText(json, true).get(5, TimeUnit.SECONDS);
        }

        boolean awaitClosed(long timeoutMs) throws InterruptedException {
            return closedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        JsonNode awaitMessage(String expectedType) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                long remainingMs = Math.max(10, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                JsonNode msg = inbox.poll(remainingMs, TimeUnit.MILLISECONDS);
                if (msg != null && expectedType.equals(msg.path("type").asText())) {
                    return msg;
                }
            }
            throw new AssertionError("Timed out waiting for WebSocket message type=" + expectedType);
        }

        JsonNode awaitGameStateWhere(java.util.function.Predicate<JsonNode> predicate) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                long remainingMs = Math.max(10, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                JsonNode msg = inbox.poll(remainingMs, TimeUnit.MILLISECONDS);
                if (msg != null && "GAME_STATE".equals(msg.path("type").asText()) && predicate.test(msg)) {
                    return msg;
                }
            }
            throw new AssertionError("Timed out waiting for matching GAME_STATE");
        }

        @Override
        public void close() {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ws.abort();
            }
        }
    }

    private int startServerWithAiPort(int aiPort) {
        return startServer(aiPort, ServerConfig.TransportConfig.defaults());
    }

    private int startServer(int aiPort, ServerConfig.TransportConfig transport) {
        return startServer(aiPort, transport, ServerConfig.LoggingConfig.disabled());
    }

    private int startServer(
        int aiPort,
        ServerConfig.TransportConfig transport,
        ServerConfig.LoggingConfig logging
    ) {
        ServerConfig cfg = new ServerConfig(
            "127.0.0.1",
            0,
            20,
            2,
            20,
            3,
            50,
            transport,
            logging,
            new ServerConfig.PublicIpLookupConfig(false, "https://api.ipify.org", 1000),
            new ServerConfig.AiServerConfig("127.0.0.1", aiPort, 1000, 1500, 4)
        );
        webServer = new WebServer(cfg);
        return webServer.start();
    }

    private int startMockAiServer(
        AtomicBoolean healthyFlag,
        AtomicInteger outputSizeFlag,
        AtomicBoolean failPredictFlag
    ) throws IOException {
        mockAiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockAiServer.setExecutor(Executors.newCachedThreadPool());

        mockAiServer.createContext("/health", ex -> {
            if (healthyFlag.get()) {
                sendJson(ex, 200, "{\"status\":\"ok\"}");
            } else {
                sendJson(ex, 503, "{\"status\":\"down\"}");
            }
        });
        mockAiServer.createContext("/info", ex -> sendJson(
            ex,
            200,
            "{\"input_size\":9,\"output_size\":" + outputSizeFlag.get() + ",\"num_layers\":3,\"parameters\":23081}"
        ));
        mockAiServer.createContext("/predict", ex -> {
            if (failPredictFlag.get()) {
                sendJson(ex, 500, "{\"error\":\"temporary ai failure\"}");
                return;
            }
            double[] scores = new double[41];
            scores[4] = 0.85;
            scores[0] = 0.70;
            scores[2] = 0.65;
            scores[6] = 0.60;
            scores[8] = 0.55;
            scores[BoardTopology.moveToActionIndex(Move.movement(4, 1))] = 0.90;
            for (int i = 0; i < 41; i++) {
                if (scores[i] == 0.0) {
                    scores[i] = 0.01 * (i + 1) / 41.0;
                }
            }
            String body = "{\"output\":" + MAPPER.writeValueAsString(scores) + ",\"latency_us\":19}";
            sendJson(ex, 200, body);
        });
        mockAiServer.start();
        return mockAiServer.getAddress().getPort();
    }

    private static int findClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void restEndpointsServeStaticAssetsPathTraversalHealthConfigAndAiStatus() throws Exception {
        AtomicBoolean aiUp = new AtomicBoolean(true);
        AtomicInteger aiOutputs = new AtomicInteger(41);
        int aiPort = startMockAiServer(aiUp, aiOutputs, new AtomicBoolean(false));
        int port = startServerWithAiPort(aiPort);

        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> root = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("Mobile Tic-Tac-Toe"));

        HttpResponse<String> css = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/styles.css")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, css.statusCode());
        assertTrue(css.body().contains(".board-wrapper"));

        HttpResponse<String> js = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/app.js")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, js.statusCode());
        assertTrue(js.body().contains("JOIN_LOBBY"));

        // Path traversal must never expose files outside /web.
        HttpResponse<String> traversal = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/..%2Fpom.xml")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(traversal.statusCode() == 400 || traversal.statusCode() == 404);

        // /api/health returns build version and live metrics.
        HttpResponse<String> health = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, health.statusCode());
        JsonNode healthJson = MAPPER.readTree(health.body());
        assertEquals("ok", healthJson.get("status").asText());
        assertEquals(BuildInfo.version(), healthJson.get("version").asText());
        assertEquals(0, healthJson.get("activeMatches").asInt());

        // /api/config exposes public gameplay bounds synchronized with ServerConfig.
        HttpResponse<String> cfgResp = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/config")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, cfgResp.statusCode());
        JsonNode cfgJson = MAPPER.readTree(cfgResp.body());
        assertEquals(BuildInfo.version(), cfgJson.get("version").asText());
        assertEquals(2, cfgJson.get("minUsernameLength").asInt());
        assertEquals(20, cfgJson.get("maxUsernameLength").asInt());
        assertEquals(3, cfgJson.get("repetitionLimit").asInt());
        assertEquals(50, cfgJson.get("maxMovementPlies").asInt());

        // /api/ai/status: online -> incompatible (9->9) -> offline.
        HttpResponse<String> aiOnline = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, aiOnline.statusCode());
        JsonNode onlineJson = MAPPER.readTree(aiOnline.body());
        assertTrue(onlineJson.get("available").asBoolean());
        assertEquals(9, onlineJson.get("inputSize").asInt());
        assertEquals(41, onlineJson.get("outputSize").asInt());

        aiOutputs.set(9);
        HttpResponse<String> aiIncompat = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        JsonNode incompatJson = MAPPER.readTree(aiIncompat.body());
        assertFalse(incompatJson.get("available").asBoolean());
        assertTrue(incompatJson.get("healthy").asBoolean());
        assertFalse(incompatJson.get("compatible").asBoolean());

        aiUp.set(false);
        HttpResponse<String> aiDown = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, aiDown.statusCode());
        assertFalse(MAPPER.readTree(aiDown.body()).get("available").asBoolean());
    }

    @Test
    void webSocketValidatesMalformedPayloadsAndRejectsOversizedFrames() throws Exception {
        int port = startServer(findClosedPort(), new ServerConfig.TransportConfig(65_536L, 1_024L, 60));

        try (TestWsClient client = TestWsClient.connect(port)) {
            client.send("{broken json");
            assertEquals("BAD_REQUEST", client.awaitMessage("ERROR").get("code").asText());

            client.send("[1, 2, 3]");
            assertEquals("BAD_REQUEST", client.awaitMessage("ERROR").get("code").asText());

            client.send("{\"foo\":\"bar\"}");
            assertEquals("BAD_REQUEST", client.awaitMessage("ERROR").get("code").asText());

            client.send("{\"type\":\"DOES_NOT_EXIST\"}");
            assertEquals("UNKNOWN_MESSAGE_TYPE", client.awaitMessage("ERROR").get("code").asText());

            client.send("{\"type\":\"JOIN_LOBBY\",\"username\":12345}");
            assertEquals("INVALID_USERNAME", client.awaitMessage("ERROR").get("code").asText());

            client.send("{\"type\":\"JOIN_LOBBY\",\"username\":\"ValidUser\"}");
            assertEquals("ValidUser", client.awaitMessage("WELCOME").get("username").asText());

            client.send("{\"type\":\"START_AI_GAME\",\"playerSymbol\":\"Z\"}");
            assertEquals("BAD_REQUEST", client.awaitMessage("ERROR").get("code").asText());

            client.send("{\"type\":\"START_AI_GAME\",\"firstTurn\":\"NOBODY\"}");
            assertEquals("BAD_REQUEST", client.awaitMessage("ERROR").get("code").asText());

            // Default START_AI_GAME with omitted fields reaches MatchManager and reports AI_UNAVAILABLE.
            client.send("{\"type\":\"START_AI_GAME\"}");
            assertEquals("AI_UNAVAILABLE", client.awaitMessage("ERROR").get("code").asText());

            client.send("{\"type\":\"PLACE_PIECE\",\"to\":\"four\"}");
            assertEquals("BAD_REQUEST", client.awaitMessage("ERROR").get("code").asText());

            client.send("{\"type\":\"PLACE_PIECE\",\"to\":9}");
            assertEquals("ILLEGAL_MOVE", client.awaitMessage("ERROR").get("code").asText());

            client.send("{\"type\":\"MOVE_PIECE\",\"from\":0,\"to\":0}");
            assertEquals("ILLEGAL_MOVE", client.awaitMessage("ERROR").get("code").asText());

            client.send("{\"type\":\"MOVE_PIECE\",\"from\":\"a\",\"to\":4}");
            assertEquals("BAD_REQUEST", client.awaitMessage("ERROR").get("code").asText());

            // Oversized frame (> 1,024 bytes) causes Jetty to close the socket with message-too-big.
            String oversized = "{\"type\":\"JOIN_LOBBY\",\"username\":\"" + "A".repeat(2_048) + "\"}";
            client.send(oversized);
            assertTrue(client.awaitClosed(3000), "Expected server to close socket on oversized WebSocket frame");
        }
    }

    @Test
    void endToEndOnlinePvpMovementPhaseHealthCountersRematchAndAbruptDisconnect() throws Exception {
        int port = startServerWithAiPort(findClosedPort());
        HttpClient http = HttpClient.newHttpClient();

        try (TestWsClient alice = TestWsClient.connect(port)) {
            alice.send("{\"type\":\"JOIN_LOBBY\",\"username\":\"Alice\"}");
            alice.awaitMessage("WELCOME");

            try (TestWsClient bob = TestWsClient.connect(port)) {
                bob.send("{\"type\":\"JOIN_LOBBY\",\"username\":\"Bob\"}");
                bob.awaitMessage("WELCOME");

                // Queue, verify live /api/health queueSize=1, cancel, then re-queue.
                alice.send("{\"type\":\"QUEUE_ONLINE\"}");
                assertEquals(1, alice.awaitMessage("QUEUE_JOINED").get("position").asInt());

                JsonNode queuedHealth = MAPPER.readTree(http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                ).body());
                assertEquals(2, queuedHealth.get("connectedSessions").asInt());
                assertEquals(1, queuedHealth.get("queueSize").asInt());
                assertEquals(0, queuedHealth.get("activeMatches").asInt());

                alice.send("{\"type\":\"CANCEL_QUEUE\"}");
                alice.awaitMessage("QUEUE_CANCELLED");

                alice.send("{\"type\":\"QUEUE_ONLINE\"}");
                alice.awaitMessage("QUEUE_JOINED");
                bob.send("{\"type\":\"QUEUE_ONLINE\"}");
                alice.awaitMessage("MATCH_FOUND");
                bob.awaitMessage("MATCH_FOUND");
                alice.awaitMessage("GAME_STATE");
                bob.awaitMessage("GAME_STATE");

                JsonNode matchHealth = MAPPER.readTree(http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                ).body());
                assertEquals(0, matchHealth.get("queueSize").asInt());
                assertEquals(1, matchHealth.get("activeMatches").asInt());

                // Play 6 placements into Phase 2 (X={0,4,7}, O={1,2,6}), then X wins via MOVE_PIECE(7->8).
                int[][] placements = {{0, 1}, {4, 2}, {7, 6}};
                for (int[] pair : placements) {
                    alice.send("{\"type\":\"PLACE_PIECE\",\"to\":" + pair[0] + "}");
                    alice.awaitMessage("GAME_STATE");
                    bob.awaitMessage("GAME_STATE");
                    bob.send("{\"type\":\"PLACE_PIECE\",\"to\":" + pair[1] + "}");
                    alice.awaitMessage("GAME_STATE");
                    bob.awaitMessage("GAME_STATE");
                }

                alice.send("{\"type\":\"MOVE_PIECE\",\"from\":7,\"to\":8}");
                JsonNode winAlice = alice.awaitMessage("GAME_STATE");
                JsonNode winBob = bob.awaitMessage("GAME_STATE");
                assertEquals("X_WINS", winAlice.get("status").asText());
                assertEquals("X_WINS", winBob.get("status").asText());
                assertEquals(1, winAlice.get("movementPlies").asInt());

                // Mutual rematch over real WebSockets.
                alice.send("{\"type\":\"PLAY_AGAIN\"}");
                assertEquals("Alice", bob.awaitMessage("REMATCH_REQUESTED").get("byUsername").asText());
                bob.send("{\"type\":\"PLAY_AGAIN\"}");
                JsonNode restarted = alice.awaitMessage("GAME_STATE");
                bob.awaitMessage("GAME_STATE");
                assertEquals("IN_PROGRESS", restarted.get("status").asText());
                assertEquals("O", restarted.get("currentTurn").asText());
            }

            // Exiting Bob's try-with-resources closes his WebSocket -> Alice receives OPPONENT_LEFT.
            JsonNode left = alice.awaitMessage("OPPONENT_LEFT");
            assertTrue(left.get("message").asText().contains("Bob"));
        }
    }

    @Test
    void endToEndLocalPvpThroughMovementPhaseAndLeave() throws Exception {
        int port = startServerWithAiPort(findClosedPort());

        try (TestWsClient client = TestWsClient.connect(port)) {
            client.send("{\"type\":\"JOIN_LOBBY\",\"username\":\"LocalHost\"}");
            client.awaitMessage("WELCOME");

            client.send("{\"type\":\"START_LOCAL_GAME\"}");
            assertEquals("LOCAL_PVP", client.awaitMessage("MATCH_FOUND").get("mode").asText());
            client.awaitMessage("GAME_STATE");

            int[] placements = {0, 1, 4, 2, 7, 6};
            for (int cell : placements) {
                client.send("{\"type\":\"PLACE_PIECE\",\"to\":" + cell + "}");
                client.awaitMessage("GAME_STATE");
            }

            client.send("{\"type\":\"MOVE_PIECE\",\"from\":7,\"to\":8}");
            JsonNode win = client.awaitMessage("GAME_STATE");
            assertEquals("X_WINS", win.get("status").asText());

            client.send("{\"type\":\"LEAVE_MATCH\"}");
            client.awaitMessage("MATCH_LEFT");
        }
    }

    @Test
    void endToEndVsAiHandlesOfflineOnlineTransientErrorRetryAndMovementPhase() throws Exception {
        AtomicBoolean aiUp = new AtomicBoolean(false);
        AtomicInteger aiOutputs = new AtomicInteger(41);
        AtomicBoolean failPredict = new AtomicBoolean(false);
        int aiPort = startMockAiServer(aiUp, aiOutputs, failPredict);
        int port = startServerWithAiPort(aiPort);

        try (TestWsClient client = TestWsClient.connect(port)) {
            client.send("{\"type\":\"JOIN_LOBBY\",\"username\":\"Tester\"}");
            JsonNode welcome = client.awaitMessage("WELCOME");
            assertFalse(welcome.get("aiStatus").get("available").asBoolean());

            // Offline AI returns clean AI_UNAVAILABLE error.
            client.send("{\"type\":\"START_AI_GAME\",\"playerSymbol\":\"X\",\"firstTurn\":\"HUMAN\"}");
            assertEquals("AI_UNAVAILABLE", client.awaitMessage("ERROR").get("code").asText());

            // Bring AI online; AI moves first as O (places center 4).
            aiUp.set(true);
            client.send("{\"type\":\"START_AI_GAME\",\"playerSymbol\":\"X\",\"firstTurn\":\"AI\"}");
            assertEquals("VS_AI", client.awaitMessage("MATCH_FOUND").get("mode").asText());

            JsonNode afterAi1 = client.awaitGameStateWhere(
                s -> !s.get("aiThinking").asBoolean() && "X".equals(s.get("currentTurn").asText())
            );
            assertEquals("O", afterAi1.get("board").get(4).asText());
            assertNotNull(afterAi1.get("aiMeta"));
            assertEquals(19L, afterAi1.get("aiMeta").get("latencyUs").asLong());
            assertEquals(41, afterAi1.get("aiMeta").get("actionProbs").size());

            // Human plays 0 while AI fails transiently -> AI_ERROR -> RETRY_AI_MOVE recovers (O plays 2).
            failPredict.set(true);
            client.send("{\"type\":\"PLACE_PIECE\",\"to\":0}");
            assertEquals("AI_ERROR", client.awaitMessage("ERROR").get("code").asText());

            failPredict.set(false);
            client.send("{\"type\":\"RETRY_AI_MOVE\"}");
            JsonNode afterRetry = client.awaitGameStateWhere(
                s -> !s.get("aiThinking").asBoolean() && s.get("piecesPlaced").get("O").asInt() == 2
            );
            assertEquals("O", afterRetry.get("board").get(2).asText());

            // Human blocks diagonal on 6 -> AI places 8 (O={2,4,8}, no win); human places 5 (X={0,5,6}, no win).
            // Both now have 3 pieces -> Phase 2 (MOVEMENT): AI slides 4->1 (highest legal movement score 0.90).
            client.send("{\"type\":\"PLACE_PIECE\",\"to\":6}");
            client.awaitGameStateWhere(
                s -> !s.get("aiThinking").asBoolean() && s.get("piecesPlaced").get("O").asInt() == 3
            );
            client.send("{\"type\":\"PLACE_PIECE\",\"to\":5}");
            JsonNode movementReply = client.awaitGameStateWhere(
                s -> !s.get("aiThinking").asBoolean()
                    && "MOVEMENT".equals(s.get("phase").asText())
                    && s.get("movementPlies").asInt() == 1
            );
            assertEquals(4, movementReply.get("lastMove").get("from").asInt());
            assertEquals(1, movementReply.get("lastMove").get("to").asInt());
        }
    }

    @Test
    void endToEndJsonlAuditLogRecordsServerLifecycleHttpAccessConnectionsModesAndProxyTrust(@TempDir Path tempDir)
        throws Exception {
        // Verify client IP extraction honors X-Forwarded-For ONLY when trustProxyHeaders=true.
        assertEquals("198.51.100.7", GameWebSocketHandler.resolveClientIp("198.51.100.7", "203.0.113.9, 10.0.0.1", false));
        assertEquals("203.0.113.9", GameWebSocketHandler.resolveClientIp("198.51.100.7", "203.0.113.9, 10.0.0.1", true));

        Path auditFile = tempDir.resolve("audit.log");
        ServerConfig.LoggingConfig logging = new ServerConfig.LoggingConfig(
            true,
            auditFile.toString(),
            true,
            false,
            10,
            5
        );
        int port = startServer(findClosedPort(), ServerConfig.TransportConfig.defaults(), logging);
        webServer.auditLogger().logServerStart(
            new com.tictactoe.net.ResolvedAddresses("127.0.0.1", port, "192.168.1.50", "100.64.0.10", null),
            webServer.config().aiServer().baseUrl(),
            com.tictactoe.ai.AiStatus.offline("test")
        );

        HttpClient http = HttpClient.newHttpClient();
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                .header("X-Forwarded-For", "9.9.9.9")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        try (TestWsClient client = TestWsClient.connect(port)) {
            client.send("{\"type\":\"JOIN_LOBBY\",\"username\":\"Auditor\"}");
            client.awaitMessage("WELCOME");
            client.send("{\"type\":\"START_LOCAL_GAME\"}");
            client.awaitMessage("MATCH_FOUND");
            client.awaitMessage("GAME_STATE");
            // Quick X win on top row {0, 1, 2}
            int[] moves = {0, 3, 1, 4, 2};
            for (int cell : moves) {
                client.send("{\"type\":\"PLACE_PIECE\",\"to\":" + cell + "}");
                client.awaitMessage("GAME_STATE");
            }
            client.send("{\"type\":\"LEAVE_MATCH\"}");
            client.awaitMessage("MATCH_LEFT");
        }

        webServer.stopWithAudit("TEST_STOP", 123L);
        webServer = null;

        List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
        assertTrue(lines.size() >= 7, "Expected complete audit trail, got " + lines.size() + " lines");

        List<JsonNode> events = lines.stream().map(line -> {
            try {
                return MAPPER.readTree(line);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).toList();

        assertEquals("SERVER_START", events.get(0).get("event").asText());
        assertEquals("SERVER_STOP", events.get(events.size() - 1).get("event").asText());

        // HTTP_ACCESS ignored spoofed X-Forwarded-For (trustProxyHeaders=false) and recorded real socket IP 127.0.0.1.
        JsonNode httpEv = events.stream()
            .filter(e -> "HTTP_ACCESS".equals(e.get("event").asText()) && "/api/health".equals(e.get("path").asText()))
            .findFirst()
            .orElseThrow();
        assertEquals("127.0.0.1", httpEv.get("ip").asText());
        assertEquals(200, httpEv.get("status").asInt());

        assertTrue(events.stream().anyMatch(
            e -> "WS_CONNECT".equals(e.get("event").asText()) && "127.0.0.1".equals(e.get("ip").asText())
        ));
        assertTrue(events.stream().anyMatch(
            e -> "LOBBY_JOIN".equals(e.get("event").asText()) && "Auditor".equals(e.get("username").asText())
        ));
        assertTrue(events.stream().anyMatch(
            e -> "MATCH_START".equals(e.get("event").asText()) && "LOCAL_PVP".equals(e.get("mode").asText())
        ));

        // Exactly ONE MATCH_END must be emitted (LEAVE_MATCH after X_WINS must not duplicate it).
        List<JsonNode> matchEnds = events.stream()
            .filter(e -> "MATCH_END".equals(e.get("event").asText()))
            .toList();
        assertEquals(1, matchEnds.size());
        assertEquals("X_WINS", matchEnds.get(0).get("status").asText());
        assertEquals("COMPLETED", matchEnds.get(0).get("reason").asText());

        assertTrue(events.stream().anyMatch(
            e -> "WS_DISCONNECT".equals(e.get("event").asText()) && "Auditor".equals(e.get("username").asText())
        ));
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
