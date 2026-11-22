package com.tictactoe.web;

import com.tictactoe.ai.AiStatus;
import com.tictactoe.config.BuildInfo;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.match.MatchManager;
import io.javalin.Javalin;
import java.util.Objects;

/**
 * Registers HTTP REST endpoints for server health, public gameplay configuration, and AI status inspection.
 */
public final class RestController {

    private final ServerConfig config;
    private final MatchManager matchManager;

    public RestController(ServerConfig config, MatchManager matchManager) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.matchManager = Objects.requireNonNull(matchManager, "matchManager must not be null");
    }

    public void register(Javalin app) {
        app.get("/api/health", ctx -> {
            ProtocolMessages.HealthResponse response = new ProtocolMessages.HealthResponse(
                "ok",
                BuildInfo.version(),
                matchManager.connectedSessionCount(),
                matchManager.queueSize(),
                matchManager.activeMatchCount()
            );
            ctx.json(response);
        });

        app.get("/api/config", ctx -> {
            ProtocolMessages.PublicConfigResponse response = new ProtocolMessages.PublicConfigResponse(
                BuildInfo.version(),
                config.minUsernameLength(),
                config.maxUsernameLength(),
                config.repetitionLimit(),
                config.maxMovementPlies()
            );
            ctx.json(response);
        });

        app.get("/api/ai/status", ctx -> {
            AiStatus status = matchManager.checkAiStatus();
            ctx.json(ProtocolMessages.AiStatusDto.from(status));
        });
    }
}
