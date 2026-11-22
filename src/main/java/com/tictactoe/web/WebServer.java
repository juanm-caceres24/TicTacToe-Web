package com.tictactoe.web;

import com.tictactoe.ai.NeuralNetworkClient;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.match.MatchManager;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.time.Duration;
import java.util.Objects;

/**
 * Standalone Javalin 6 web server hosting static web assets, REST endpoints, and WebSockets on a single port.
 */
public final class WebServer implements AutoCloseable {

    private static final long MAX_HTTP_REQUEST_BYTES = 64L * 1024L;
    private static final long MAX_WS_TEXT_BYTES = 8L * 1024L;
    private static final Duration WS_IDLE_TIMEOUT = Duration.ofMinutes(5);

    private final ServerConfig config;
    private final NeuralNetworkClient aiClient;
    private final MatchManager matchManager;
    private final Javalin app;
    private volatile boolean started;

    public WebServer(ServerConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.aiClient = new NeuralNetworkClient(config.aiServer());
        this.matchManager = new MatchManager(config, aiClient);
        this.app = createJavalinApp();
    }

    private Javalin createJavalinApp() {
        Javalin javalin = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = MAX_HTTP_REQUEST_BYTES;
            cfg.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
            });
            cfg.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setIdleTimeout(WS_IDLE_TIMEOUT);
                factory.setMaxTextMessageSize(MAX_WS_TEXT_BYTES);
            });
        });

        new RestController(matchManager).register(javalin);
        new GameWebSocketHandler(matchManager).register(javalin);
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
        }
        return app.port();
    }

    public int port() {
        return app.port();
    }

    public ServerConfig config() {
        return config;
    }

    public NeuralNetworkClient aiClient() {
        return aiClient;
    }

    public MatchManager matchManager() {
        return matchManager;
    }

    @Override
    public synchronized void close() {
        try {
            if (started) {
                app.stop();
                started = false;
            }
        } finally {
            matchManager.close();
        }
    }
}
