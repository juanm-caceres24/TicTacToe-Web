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
        ServerConfig.TransportConfig transport = config.transport();
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

        new RestController(config, matchManager).register(javalin);
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
