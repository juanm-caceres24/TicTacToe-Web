package com.tictactoe.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.engine.GameEngine;
import com.tictactoe.engine.MoveEncoder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;

/**
 * HTTP/1.1 + JSON client for the external {@code NeuralNetwork} inference server.
 *
 * <p>Consumes:
 * <ul>
 *   <li>{@code GET /health} -> {@code {"status":"ok"}}</li>
 *   <li>{@code GET /info} -> {@code {"input_size":9, "output_size":41, ...}}</li>
 *   <li>{@code POST /predict} -> request {@code {"input":[9 floats], "include_neurons":false}},
 *       response {@code {"output":[41 floats], "latency_us":12}}</li>
 * </ul>
 */
public final class NeuralNetworkClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ServerConfig.AiServerConfig config;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public NeuralNetworkClient(ServerConfig.AiServerConfig config) {
        this.config = Objects.requireNonNull(config, "aiServer config must not be null");
        this.requestTimeout = Duration.ofMillis(config.requestTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
            .build();
    }

    public String baseUrl() {
        return config.baseUrl();
    }

    /**
     * Checks connectivity, health ({@code GET /health}), and model dimensions ({@code GET /info}).
     * Never throws on network/server failure; always returns a descriptive {@link AiStatus}.
     */
    public AiStatus checkStatus() {
        HttpResponse<String> healthResp;
        try {
            healthResp = sendGet("/health");
        } catch (AiClientException e) {
            return AiStatus.offline(e.getMessage());
        }

        if (healthResp.statusCode() != 200) {
            return AiStatus.unhealthy("GET /health returned HTTP " + healthResp.statusCode());
        }

        try {
            JsonNode healthJson = MAPPER.readTree(healthResp.body());
            String status = healthJson.path("status").asText("");
            if (!"ok".equalsIgnoreCase(status)) {
                return AiStatus.unhealthy("GET /health returned status='" + status + "'");
            }
        } catch (IOException e) {
            return AiStatus.unhealthy("Invalid JSON from GET /health");
        }

        HttpResponse<String> infoResp;
        try {
            infoResp = sendGet("/info");
        } catch (AiClientException e) {
            return AiStatus.unhealthy("GET /info failed: " + e.getMessage());
        }

        if (infoResp.statusCode() != 200) {
            return AiStatus.unhealthy("GET /info returned HTTP " + infoResp.statusCode());
        }

        try {
            JsonNode infoJson = MAPPER.readTree(infoResp.body());
            if (!infoJson.has("input_size") || !infoJson.get("input_size").isIntegralNumber()
                || !infoJson.has("output_size") || !infoJson.get("output_size").isIntegralNumber()) {
                return AiStatus.unhealthy("GET /info missing integer input_size or output_size");
            }

            int inputSize = infoJson.get("input_size").asInt();
            int outputSize = infoJson.get("output_size").asInt();
            int numLayers = infoJson.path("num_layers").isIntegralNumber()
                ? infoJson.get("num_layers").asInt()
                : -1;
            long parameters = infoJson.path("parameters").isIntegralNumber()
                ? infoJson.get("parameters").asLong()
                : -1L;

            if (inputSize == MoveEncoder.INPUT_SIZE && outputSize == MoveEncoder.OUTPUT_SIZE) {
                return AiStatus.ready(inputSize, outputSize, numLayers, parameters);
            }
            return AiStatus.incompatible(inputSize, outputSize, numLayers, parameters);
        } catch (IOException e) {
            return AiStatus.unhealthy("Invalid JSON from GET /info");
        }
    }

    /**
     * Queries {@code POST /predict} for the current state of {@code engine} and applies
     * legal move masking via {@link MoveEncoder#selectBestLegalMove(GameEngine, double[])}.
     *
     * @throws AiClientException on connection failure, timeout, HTTP error, or malformed output vector
     */
    public AiPrediction predictMove(GameEngine engine) throws AiClientException {
        Objects.requireNonNull(engine, "engine must not be null");
        if (engine.getStatus().isTerminal()) {
            throw new IllegalStateException("Cannot request AI move for a terminated game: " + engine.getStatus());
        }

        double[] encodedInput = MoveEncoder.encodeBoard(engine);
        String payload = buildPredictPayload(encodedInput);

        HttpResponse<String> response = sendPost("/predict", payload);
        if (response.statusCode() != 200) {
            String serverError = extractErrorMessage(response.body());
            String detail = serverError != null
                ? "POST /predict failed (HTTP " + response.statusCode() + "): " + serverError
                : "POST /predict failed with HTTP " + response.statusCode();
            throw new AiClientException(AiClientException.Reason.HTTP_ERROR, response.statusCode(), detail);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new AiClientException(
                AiClientException.Reason.INVALID_RESPONSE,
                "Malformed JSON response from POST /predict",
                e
            );
        }

        JsonNode outputNode = root.get("output");
        if (outputNode == null || !outputNode.isArray()) {
            throw new AiClientException(
                AiClientException.Reason.INVALID_RESPONSE,
                "POST /predict response missing numeric 'output' array"
            );
        }
        if (outputNode.size() != MoveEncoder.OUTPUT_SIZE) {
            throw new AiClientException(
                AiClientException.Reason.INCOMPATIBLE_MODEL,
                "Expected " + MoveEncoder.OUTPUT_SIZE + " output scores, received " + outputNode.size()
            );
        }

        double[] rawScores = new double[MoveEncoder.OUTPUT_SIZE];
        for (int i = 0; i < MoveEncoder.OUTPUT_SIZE; i++) {
            JsonNode elem = outputNode.get(i);
            if (!elem.isNumber()) {
                throw new AiClientException(
                    AiClientException.Reason.INVALID_RESPONSE,
                    "Non-numeric value at output[" + i + "]"
                );
            }
            rawScores[i] = elem.asDouble();
        }

        long latencyUs = root.path("latency_us").isIntegralNumber()
            ? root.get("latency_us").asLong()
            : -1L;

        MoveEncoder.MaskedSelection selection = MoveEncoder.selectBestLegalMove(engine, rawScores);
        return new AiPrediction(selection, latencyUs, rawScores);
    }

    private String buildPredictPayload(double[] input) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode inputArray = root.putArray("input");
        for (double v : input) {
            inputArray.add(v);
        }
        root.put("include_neurons", false);
        return root.toString();
    }

    private HttpResponse<String> sendGet(String path) throws AiClientException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + path))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .GET()
            .build();
        return execute(request, "GET " + path);
    }

    private HttpResponse<String> sendPost(String path, String jsonBody) throws AiClientException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + path))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        return execute(request, "POST " + path);
    }

    private HttpResponse<String> execute(HttpRequest request, String operation) throws AiClientException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AiClientException(
                AiClientException.Reason.TIMEOUT,
                operation + " timed out after " + config.requestTimeoutMs() + " ms",
                e
            );
        } catch (IOException e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new AiClientException(
                AiClientException.Reason.UNREACHABLE,
                operation + " failed (" + detail + ")",
                e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiClientException(
                AiClientException.Reason.UNREACHABLE,
                operation + " was interrupted",
                e
            );
        }
    }

    private static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.has("error") && node.get("error").isTextual()) {
                return node.get("error").asText();
            }
        } catch (IOException ignored) {
            // Non-JSON error body; fall back to HTTP status message.
        }
        return null;
    }
}
