package com.tictactoe.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

        private TestWsClient(WebSocket ws, BlockingQueue<JsonNode> inbox) {
            this.ws = ws;
            this.inbox = inbox;
        }

        static TestWsClient connect(int port) throws Exception {
            BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
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
                })
                .get(5, TimeUnit.SECONDS);

            return new TestWsClient(ws, queue);
        }

        void send(String json) throws Exception {
            ws.sendText(json, true).get(5, TimeUnit.SECONDS);
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
        ServerConfig cfg = new ServerConfig(
            "127.0.0.1",
            0,
            20,
            20,
            3,
            50,
            new ServerConfig.PublicIpLookupConfig(false, "https://api.ipify.org", 1000),
            new ServerConfig.AiServerConfig("127.0.0.1", aiPort, 1000, 1500)
        );
        webServer = new WebServer(cfg);
        return webServer.start();
    }

    private int startMockAiServer(AtomicBoolean healthyFlag) throws IOException {
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
            "{\"input_size\":9,\"output_size\":41,\"num_layers\":3,\"parameters\":23081}"
        ));
        mockAiServer.createContext("/predict", ex -> {
            // Assign descending preference preferring center (4) in placement and legal slides in movement.
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
    void restEndpointsServeRootHtmlHealthAndLiveAiStatus() throws Exception {
        AtomicBoolean aiUp = new AtomicBoolean(true);
        int aiPort = startMockAiServer(aiUp);
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

        HttpResponse<String> health = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, health.statusCode());
        JsonNode healthJson = MAPPER.readTree(health.body());
        assertEquals("ok", healthJson.get("status").asText());
        assertEquals(0, healthJson.get("activeMatches").asInt());

        HttpResponse<String> aiOnline = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, aiOnline.statusCode());
        JsonNode onlineJson = MAPPER.readTree(aiOnline.body());
        assertTrue(onlineJson.get("available").asBoolean());
        assertEquals(9, onlineJson.get("inputSize").asInt());
        assertEquals(41, onlineJson.get("outputSize").asInt());

        // Toggle mock AI down -> /api/ai/status reports available=false without 500 error.
        aiUp.set(false);
        HttpResponse<String> aiDown = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, aiDown.statusCode());
        assertFalse(MAPPER.readTree(aiDown.body()).get("available").asBoolean());
    }

    @Test
    void endToEndOnlinePvpMatchOverRealWebSockets() throws Exception {
        int closedAiPort = findClosedPort();
        int port = startServerWithAiPort(closedAiPort);

        try (TestWsClient alice = TestWsClient.connect(port);
             TestWsClient bob = TestWsClient.connect(port)) {

            // Invalid JSON returns clean ERROR without disconnecting.
            alice.send("{broken json");
            JsonNode badJsonErr = alice.awaitMessage("ERROR");
            assertEquals("BAD_REQUEST", badJsonErr.get("code").asText());

            alice.send("{\"type\":\"JOIN_LOBBY\",\"username\":\"Alice\"}");
            bob.send("{\"type\":\"JOIN_LOBBY\",\"username\":\"Bob\"}");
            assertEquals("Alice", alice.awaitMessage("WELCOME").get("username").asText());
            assertEquals("Bob", bob.awaitMessage("WELCOME").get("username").asText());

            alice.send("{\"type\":\"QUEUE_ONLINE\"}");
            assertEquals(1, alice.awaitMessage("QUEUE_JOINED").get("position").asInt());

            bob.send("{\"type\":\"QUEUE_ONLINE\"}");
            JsonNode aliceMatch = alice.awaitMessage("MATCH_FOUND");
            JsonNode bobMatch = bob.awaitMessage("MATCH_FOUND");
            assertEquals("X", aliceMatch.get("yourSymbol").asText());
            assertEquals("O", bobMatch.get("yourSymbol").asText());

            JsonNode initialState = alice.awaitMessage("GAME_STATE");
            assertEquals("PLACEMENT", initialState.get("phase").asText());
            assertEquals(9, initialState.get("legalMoves").size());
            bob.awaitMessage("GAME_STATE");

            // Play a complete win for Alice (X: 0, 4, 8; O: 1, 2).
            alice.send("{\"type\":\"PLACE_PIECE\",\"to\":0}");
            alice.awaitMessage("GAME_STATE");
            bob.awaitMessage("GAME_STATE");

            bob.send("{\"type\":\"PLACE_PIECE\",\"to\":1}");
            alice.awaitMessage("GAME_STATE");
            bob.awaitMessage("GAME_STATE");

            alice.send("{\"type\":\"PLACE_PIECE\",\"to\":4}");
            alice.awaitMessage("GAME_STATE");
            bob.awaitMessage("GAME_STATE");

            bob.send("{\"type\":\"PLACE_PIECE\",\"to\":2}");
            alice.awaitMessage("GAME_STATE");
            bob.awaitMessage("GAME_STATE");

            alice.send("{\"type\":\"PLACE_PIECE\",\"to\":8}");
            JsonNode finalAlice = alice.awaitMessage("GAME_STATE");
            JsonNode finalBob = bob.awaitMessage("GAME_STATE");
            assertEquals("X_WINS", finalAlice.get("status").asText());
            assertEquals("X_WINS", finalBob.get("status").asText());
            assertEquals(3, finalAlice.get("winningLine").size());
        }
    }

    @Test
    void endToEndVsAiMatchHandlesAiFirstAndHumanFirstAndOfflineGracefully() throws Exception {
        AtomicBoolean aiUp = new AtomicBoolean(false);
        int aiPort = startMockAiServer(aiUp);
        int port = startServerWithAiPort(aiPort);

        try (TestWsClient client = TestWsClient.connect(port)) {
            client.send("{\"type\":\"JOIN_LOBBY\",\"username\":\"Tester\"}");
            JsonNode welcome = client.awaitMessage("WELCOME");
            assertFalse(welcome.get("aiStatus").get("available").asBoolean());

            // Starting vs AI while AI server is down returns clean AI_UNAVAILABLE error.
            client.send("{\"type\":\"START_AI_GAME\",\"playerSymbol\":\"X\",\"firstTurn\":\"HUMAN\"}");
            JsonNode offlineErr = client.awaitMessage("ERROR");
            assertEquals("AI_UNAVAILABLE", offlineErr.get("code").asText());

            // Bring AI server online and start a game where AI moves first as O.
            aiUp.set(true);
            client.send("{\"type\":\"START_AI_GAME\",\"playerSymbol\":\"X\",\"firstTurn\":\"AI\"}");
            JsonNode found = client.awaitMessage("MATCH_FOUND");
            assertEquals("VS_AI", found.get("mode").asText());
            assertEquals("X", found.get("yourSymbol").asText());

            // Wait for AI's opening move to complete (center cell 4 preferred by mock).
            JsonNode afterAiOpening = client.awaitGameStateWhere(
                state -> !state.get("aiThinking").asBoolean() && "X".equals(state.get("currentTurn").asText())
            );
            assertEquals("O", afterAiOpening.get("board").get(4).asText());
            assertNotNull(afterAiOpening.get("aiMeta"));
            assertEquals(19L, afterAiOpening.get("aiMeta").get("latencyUs").asLong());
            assertEquals(41, afterAiOpening.get("aiMeta").get("actionProbs").size());

            // Human responds on cell 0 -> AI automatically plays its second move.
            client.send("{\"type\":\"PLACE_PIECE\",\"to\":0}");
            JsonNode afterAiReply = client.awaitGameStateWhere(
                state -> !state.get("aiThinking").asBoolean()
                    && state.get("piecesPlaced").get("O").asInt() == 2
            );
            assertEquals("X", afterAiReply.get("currentTurn").asText());
            assertEquals("X", afterAiReply.get("board").get(0).asText());
            assertEquals("O", afterAiReply.get("board").get(2).asText());
        }
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
