package com.tictactoe.web;

import com.tictactoe.ai.NeuralNetworkClient;
import com.tictactoe.audit.AuditLogger;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.match.MatchManager;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Standalone Javalin 6 web server hosting static web assets, REST endpoints, WebSockets,
 * and file-backed JSONL audit logging on a single port.
 */
public final class WebServer implements AutoCloseable {

    private static final String REQ_START_NANOS_ATTR = "ttt.reqStartNanos";

    private final ServerConfig config;
    private final AuditLogger auditLogger;
    private final NeuralNetworkClient aiClient;
    private final MatchManager matchManager;
    private final Javalin app;
    private volatile boolean started;
    private volatile boolean stopped;

    public WebServer(ServerConfig config) {
        this(config, openAuditLogger(config));
    }

    public WebServer(ServerConfig config, AuditLogger auditLogger) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger must not be null");
        this.aiClient = new NeuralNetworkClient(config.aiServer());
        this.matchManager = new MatchManager(config, aiClient, auditLogger);
        this.app = createJavalinApp();
    }

    private static AuditLogger openAuditLogger(ServerConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        try {
            return AuditLogger.fromConfig(config.logging());
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to initialize audit log at '" + config.logging().filePath() + "': " + e.getMessage(),
                e
            );
        }
    }

    private Javalin createJavalinApp() {
        ServerConfig.TransportConfig transport = config.transport();
        ServerConfig.LoggingConfig logging = config.logging();

        Javalin javalin = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = transport.maxHttpRequestBytes();
            cfg.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
            });
            cfg.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setIdleTimeout(Duration.ofSeconds(transport.wsIdleTimeoutSeconds()));
                factory.setMaxTextMessageSize(transport.maxWsMessageBytes());
            });
        });

        if (logging.enabled() && logging.logHttpRequests()) {
            boolean trustProxy = logging.trustProxyHeaders();
            javalin.before(ctx -> ctx.attribute(REQ_START_NANOS_ATTR, System.nanoTime()));
            javalin.after(ctx -> {
                Long startNanos = ctx.attribute(REQ_START_NANOS_ATTR);
                long durationMs = startNanos != null
                    ? Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L)
                    : 0L;
                String clientIp = GameWebSocketHandler.resolveClientIp(
                    ctx.req().getRemoteAddr(),
                    trustProxy ? ctx.header("X-Forwarded-For") : null,
                    trustProxy
                );
                auditLogger.logHttpAccess(clientIp, ctx.method().name(), ctx.path(), ctx.statusCode(), durationMs);
            });
        }

        new RestController(config, matchManager).register(javalin);
        new GameWebSocketHandler(matchManager, logging.trustProxyHeaders()).register(javalin);
        return javalin;
    }

    /**
     * Starts listening on {@code config.host():config.port()} and returns the actual bound port
     * (useful when {@code config.port() == 0} in automated tests).
     */
    public synchronized int start() {
        if (!started) {
            app.start(config.host(), config.port());
            started = true;
            stopped = false;
        }
        return app.port();
    }

    public int port() {
        return app.port();
    }

    public ServerConfig config() {
        return config;
    }

    public AuditLogger auditLogger() {
        return auditLogger;
    }

    public NeuralNetworkClient aiClient() {
        return aiClient;
    }

    public MatchManager matchManager() {
        return matchManager;
    }

    /**
     * Stops Javalin and active matches first (flushing any remaining {@code WS_DISCONNECT} / {@code MATCH_END}
     * events), then records {@code SERVER_STOP} as the final audit entry before closing the log stream.
     */
    public synchronized void stopWithAudit(String reason, long uptimeMs) {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            if (started) {
                app.stop();
                started = false;
            }
        } finally {
            try {
                matchManager.close();
                auditLogger.logServerStop(reason, uptimeMs);
            } finally {
                auditLogger.close();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            if (started) {
                app.stop();
                started = false;
            }
        } finally {
            try {
                matchManager.close();
            } finally {
                auditLogger.close();
            }
        }
    }
}
