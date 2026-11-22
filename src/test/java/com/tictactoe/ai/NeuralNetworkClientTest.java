package com.tictactoe.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.engine.BoardTopology;
import com.tictactoe.engine.GameEngine;
import com.tictactoe.engine.Move;
import com.tictactoe.engine.Player;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NeuralNetworkClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
            mockServer = null;
        }
    }

    private HttpServer startMockServer() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.setExecutor(Executors.newCachedThreadPool());
        mockServer.start();
        return mockServer;
    }

    private NeuralNetworkClient clientFor(HttpServer server, int timeoutMs) {
        return new NeuralNetworkClient(
            new ServerConfig.AiServerConfig("127.0.0.1", server.getAddress().getPort(), timeoutMs, timeoutMs)
        );
    }

    private static int findClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void checkStatusReportsReadyForCompatibleNineToFortyOneModel() throws IOException {
        HttpServer server = startMockServer();
        server.createContext("/health", ex -> sendJson(ex, 200, "{\"status\":\"ok\"}"));
        server.createContext("/info", ex -> sendJson(
            ex,
            200,
            "{\"input_size\":9,\"output_size\":41,\"num_layers\":3,\"parameters\":23081}"
        ));

        AiStatus status = clientFor(server, 1500).checkStatus();
        assertTrue(status.isReady());
        assertTrue(status.serverReachable());
        assertTrue(status.healthy());
        assertTrue(status.modelCompatible());
        assertEquals(9, status.inputSize());
        assertEquals(41, status.outputSize());
        assertEquals(3, status.numLayers());
        assertEquals(23081L, status.parameters());
        assertEquals("ONLINE | 9->41", status.summaryText());
    }

    @Test
    void checkStatusFlagsIncompatibleModelDimensionsWithoutThrowing() throws IOException {
        HttpServer server = startMockServer();
        server.createContext("/health", ex -> sendJson(ex, 200, "{\"status\":\"ok\"}"));
        server.createContext("/info", ex -> sendJson(
            ex,
            200,
            "{\"input_size\":9,\"output_size\":9,\"num_layers\":2,\"parameters\":1500}"
        ));

        AiStatus status = clientFor(server, 1500).checkStatus();
        assertFalse(status.isReady());
        assertTrue(status.serverReachable());
        assertTrue(status.healthy());
        assertFalse(status.modelCompatible());
        assertEquals(9, status.outputSize());
        assertTrue(status.errorMessage().contains("9->41"));
        assertTrue(status.errorMessage().contains("9->9"));
    }

    @Test
    void checkStatusHandlesUnhealthyAndOfflineServersGracefully() throws IOException {
        HttpServer server = startMockServer();
        server.createContext("/health", ex -> sendJson(ex, 503, "{\"status\":\"down\"}"));

        AiStatus unhealthy = clientFor(server, 1000).checkStatus();
        assertFalse(unhealthy.isReady());
        assertTrue(unhealthy.serverReachable());
        assertFalse(unhealthy.healthy());

        int closedPort = findClosedPort();
        NeuralNetworkClient offlineClient = new NeuralNetworkClient(
            new ServerConfig.AiServerConfig("127.0.0.1", closedPort, 500, 500)
        );
        AiStatus offline = offlineClient.checkStatus();
        assertFalse(offline.isReady());
        assertFalse(offline.serverReachable());
        assertTrue(offline.summaryText().startsWith("OFFLINE"));
    }

    @Test
    void predictMovePlacementPhaseSendsPlayerRelativeInputAndMasksIllegalCells() throws Exception {
        HttpServer server = startMockServer();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        double[] mockScores = new double[41];
        mockScores[0] = 0.99;  // illegal: occupied by X
        mockScores[25] = 0.95; // illegal: movement action during placement phase
        mockScores[4] = 0.40;  // best legal placement
        mockScores[8] = 0.10;  // second legal placement
        String responseJson = "{\"output\":" + MAPPER.writeValueAsString(mockScores) + ",\"latency_us\":17}";

        server.createContext("/predict", ex -> {
            assertEquals("POST", ex.getRequestMethod());
            capturedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendJson(ex, 200, responseJson);
        });

        GameEngine engine = new GameEngine();
        engine.placePiece(0); // X placed at 0; now O's turn (O = +1, X = -1)

        AiPrediction prediction = clientFor(server, 1500).predictMove(engine);

        // Verify request payload sent to NeuralNetwork
        JsonNode sent = MAPPER.readTree(capturedBody.get());
        assertFalse(sent.get("include_neurons").asBoolean());
        JsonNode sentInput = sent.get("input");
        assertEquals(9, sentInput.size());
        double[] decodedInput = new double[9];
        for (int i = 0; i < 9; i++) {
            decodedInput[i] = sentInput.get(i).asDouble();
        }
        assertArrayEquals(new double[] {-1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0}, decodedInput, 1e-9);

        // Verify masked selection
        assertEquals(Move.placement(4), prediction.move());
        assertEquals(4, prediction.selection().bestActionIndex());
        assertEquals(17L, prediction.latencyUs());
        assertEquals(0.80, prediction.selection().normalizedLegalProbabilities()[4], 1e-9);
        assertEquals(0.20, prediction.selection().normalizedLegalProbabilities()[8], 1e-9);
        assertEquals(0.0, prediction.selection().normalizedLegalProbabilities()[0], 1e-9);
    }

    @Test
    void predictMoveMovementPhaseSelectsLegalWinningSlide() throws Exception {
        HttpServer server = startMockServer();

        // X at {0, 4, 7}, O at {1, 2, 6}, X to move -> MOVE(7->8) wins diagonal {0,4,8}
        Player[] board = {
            Player.X, Player.O, Player.O,
            null,     Player.X, null,
            Player.O, Player.X, null
        };
        GameEngine engine = GameEngine.fromState(board, Player.X);

        int winningMoveIndex = BoardTopology.moveToActionIndex(Move.movement(7, 8));
        double[] mockScores = new double[41];
        Arrays.fill(mockScores, 0.001);
        mockScores[8] = 0.99;                // illegal placement index in movement phase
        mockScores[winningMoveIndex] = 0.72; // best legal movement

        String responseJson = "{\"output\":" + MAPPER.writeValueAsString(mockScores) + ",\"latency_us\":23}";
        server.createContext("/predict", ex -> sendJson(ex, 200, responseJson));

        AiPrediction prediction = clientFor(server, 1500).predictMove(engine);
        assertEquals(Move.movement(7, 8), prediction.move());
        assertEquals(winningMoveIndex, prediction.selection().bestActionIndex());
        assertEquals(23L, prediction.latencyUs());
    }

    @Test
    void predictMoveRaisesTypedExceptionsForHttpErrorsMalformedOutputTimeoutAndUnreachable() throws IOException {
        HttpServer server = startMockServer();
        server.createContext("/predict", ex -> sendJson(ex, 400, "{\"error\":\"input must have 9 values\"}"));

        GameEngine engine = new GameEngine();

        // 1. HTTP 400 error
        AiClientException httpEx = assertThrows(
            AiClientException.class,
            () -> clientFor(server, 1500).predictMove(engine)
        );
        assertEquals(AiClientException.Reason.HTTP_ERROR, httpEx.reason());
        assertEquals(400, httpEx.httpStatus());
        assertTrue(httpEx.getMessage().contains("input must have 9 values"));

        // 2. Wrong output vector size (9 instead of 41)
        server.removeContext("/predict");
        server.createContext("/predict", ex -> sendJson(
            ex,
            200,
            "{\"output\":[0.1,0.1,0.1,0.1,0.2,0.1,0.1,0.1,0.1],\"latency_us\":9}"
        ));
        AiClientException mismatchEx = assertThrows(
            AiClientException.class,
            () -> clientFor(server, 1500).predictMove(engine)
        );
        assertEquals(AiClientException.Reason.INCOMPATIBLE_MODEL, mismatchEx.reason());

        // 3. Malformed JSON
        server.removeContext("/predict");
        server.createContext("/predict", ex -> sendJson(ex, 200, "{not valid json"));
        AiClientException malformedEx = assertThrows(
            AiClientException.class,
            () -> clientFor(server, 1500).predictMove(engine)
        );
        assertEquals(AiClientException.Reason.INVALID_RESPONSE, malformedEx.reason());

        // 4. Request timeout
        server.removeContext("/predict");
        server.createContext("/predict", ex -> {
            try {
                Thread.sleep(450);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sendJson(ex, 200, "{\"output\":[]}");
        });
        AiClientException timeoutEx = assertThrows(
            AiClientException.class,
            () -> clientFor(server, 150).predictMove(engine)
        );
        assertEquals(AiClientException.Reason.TIMEOUT, timeoutEx.reason());

        // 5. Unreachable server (closed port)
        int closedPort = findClosedPort();
        NeuralNetworkClient offlineClient = new NeuralNetworkClient(
            new ServerConfig.AiServerConfig("127.0.0.1", closedPort, 500, 500)
        );
        AiClientException unreachableEx = assertThrows(
            AiClientException.class,
            () -> offlineClient.predictMove(engine)
        );
        assertEquals(AiClientException.Reason.UNREACHABLE, unreachableEx.reason());
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
