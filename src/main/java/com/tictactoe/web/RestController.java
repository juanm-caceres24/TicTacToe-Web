package com.tictactoe.web;

import com.tictactoe.ai.AiStatus;
import com.tictactoe.match.MatchManager;
import io.javalin.Javalin;
import java.util.Objects;

/**
 * Registers HTTP REST endpoints for server health monitoring and external AI status inspection.
 */
public final class RestController {

    public static final String SERVER_VERSION = "0.4.0";

    private final MatchManager matchManager;

    public RestController(MatchManager matchManager) {
        this.matchManager = Objects.requireNonNull(matchManager, "matchManager must not be null");
    }

    public void register(Javalin app) {
        app.get("/api/health", ctx -> {
            ProtocolMessages.HealthResponse response = new ProtocolMessages.HealthResponse(
                "ok",
                SERVER_VERSION,
                matchManager.connectedSessionCount(),
                matchManager.queueSize(),
                matchManager.activeMatchCount()
            );
            ctx.json(response);
        });

        app.get("/api/ai/status", ctx -> {
            AiStatus status = matchManager.checkAiStatus();
            ctx.json(ProtocolMessages.AiStatusDto.from(status));
        });
    }
}
