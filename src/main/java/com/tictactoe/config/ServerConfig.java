package com.tictactoe.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tictactoe.engine.GameEngine;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable external server configuration loaded from {@code configs/server.json}
 * with optional CLI overrides.
 */
public record ServerConfig(
    String host,
    int port,
    int maxConcurrentMatches,
    int maxUsernameLength,
    int repetitionLimit,
    int maxMovementPlies,
    PublicIpLookupConfig publicIpLookup,
    AiServerConfig aiServer
) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    /**
     * Settings for optional external public IP detection at startup.
     */
    public record PublicIpLookupConfig(
        boolean enabled,
        String url,
        int timeoutMs
    ) {
        public PublicIpLookupConfig {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("publicIpLookup.url must not be blank");
            }
            validateHttpUri(url, "publicIpLookup.url");
            if (timeoutMs < 100) {
                throw new IllegalArgumentException("publicIpLookup.timeoutMs must be >= 100, got " + timeoutMs);
            }
        }

        public static PublicIpLookupConfig defaults() {
            return new PublicIpLookupConfig(true, "https://api.ipify.org", 2000);
        }
    }

    /**
     * Connection settings for the external {@code NeuralNetwork} HTTP inference server.
     */
    public record AiServerConfig(
        String host,
        int port,
        int connectTimeoutMs,
        int requestTimeoutMs
    ) {
        public AiServerConfig {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("aiServer.host must not be blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("aiServer.port must be in [1..65535], got " + port);
            }
            if (connectTimeoutMs < 100) {
                throw new IllegalArgumentException(
                    "aiServer.connectTimeoutMs must be >= 100, got " + connectTimeoutMs
                );
            }
            if (requestTimeoutMs < 100) {
                throw new IllegalArgumentException(
                    "aiServer.requestTimeoutMs must be >= 100, got " + requestTimeoutMs
                );
            }
        }

        public static AiServerConfig defaults() {
            return new AiServerConfig("127.0.0.1", 8080, 2000, 3000);
        }

        public String baseUrl() {
            return "http://" + host + ":" + port;
        }
    }

    public ServerConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in [0..65535], got " + port);
        }
        if (maxConcurrentMatches < 1) {
            throw new IllegalArgumentException("maxConcurrentMatches must be >= 1, got " + maxConcurrentMatches);
        }
        if (maxUsernameLength < 2 || maxUsernameLength > 64) {
            throw new IllegalArgumentException("maxUsernameLength must be in [2..64], got " + maxUsernameLength);
        }
        if (repetitionLimit < 2) {
            throw new IllegalArgumentException("repetitionLimit must be >= 2, got " + repetitionLimit);
        }
        if (maxMovementPlies < 1) {
            throw new IllegalArgumentException("maxMovementPlies must be >= 1, got " + maxMovementPlies);
        }
        Objects.requireNonNull(publicIpLookup, "publicIpLookup configuration is required");
        Objects.requireNonNull(aiServer, "aiServer configuration is required");
    }

    /**
     * Returns default configuration matching {@code configs/server.json}.
     */
    public static ServerConfig defaults() {
        return new ServerConfig(
            "0.0.0.0",
            3000,
            100,
            20,
            GameEngine.DEFAULT_REPETITION_LIMIT,
            GameEngine.DEFAULT_MAX_MOVEMENT_PLIES,
            PublicIpLookupConfig.defaults(),
            AiServerConfig.defaults()
        );
    }

    /**
     * Loads and validates a {@link ServerConfig} from a JSON file path.
     *
     * @throws IOException if the file cannot be read or contains invalid JSON/schema
     */
    public static ServerConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "config path must not be null");
        if (!Files.isRegularFile(path)) {
            throw new IOException("Configuration file not found: " + path.toAbsolutePath());
        }
        try {
            return MAPPER.readValue(Files.readString(path), ServerConfig.class);
        } catch (IOException e) {
            throw new IOException("Failed to parse configuration file '" + path + "': " + e.getMessage(), e);
        }
    }

    /**
     * Returns a new {@link ServerConfig} with any non-null CLI overrides applied.
     */
    public ServerConfig withOverrides(CliArgs cliArgs) {
        Objects.requireNonNull(cliArgs, "cliArgs must not be null");
        String effectiveHost = cliArgs.hostOverride() != null ? cliArgs.hostOverride() : this.host;
        int effectivePort = cliArgs.portOverride() != null ? cliArgs.portOverride() : this.port;

        PublicIpLookupConfig effectivePublicIp = cliArgs.publicIpEnabledOverride() != null
            ? new PublicIpLookupConfig(
                cliArgs.publicIpEnabledOverride(),
                this.publicIpLookup.url(),
                this.publicIpLookup.timeoutMs()
            )
            : this.publicIpLookup;

        AiServerConfig effectiveAi = new AiServerConfig(
            cliArgs.aiHostOverride() != null ? cliArgs.aiHostOverride() : this.aiServer.host(),
            cliArgs.aiPortOverride() != null ? cliArgs.aiPortOverride() : this.aiServer.port(),
            this.aiServer.connectTimeoutMs(),
            this.aiServer.requestTimeoutMs()
        );

        return new ServerConfig(
            effectiveHost,
            effectivePort,
            this.maxConcurrentMatches,
            this.maxUsernameLength,
            this.repetitionLimit,
            this.maxMovementPlies,
            effectivePublicIp,
            effectiveAi
        );
    }

    private static void validateHttpUri(String rawUrl, String fieldName) {
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " is not a valid URI: " + rawUrl, e);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(fieldName + " must use http or https scheme: " + rawUrl);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(fieldName + " must include a host: " + rawUrl);
        }
    }
}
