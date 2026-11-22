package com.tictactoe.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tictactoe.ai.AiStatus;
import com.tictactoe.config.BuildInfo;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.net.ResolvedAddresses;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe, file-only JSON Lines (NDJSON) audit logger with append persistence and size-based rolling rotation.
 *
 * <p>Each event is written as a single JSON object terminated by {@code '\n'} with an ISO-8601 timestamp
 * and flushed immediately so logs survive unexpected process termination. Existing log files are always
 * appended across restarts; when {@code filePath} would exceed {@code maxFileSizeBytes}, backups are shifted
 * upward ({@code file.N-1 -> file.N}, ..., {@code file -> file.1}) and a fresh active file is opened.
 */
public final class AuditLogger implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final Path logPath;
    private final long maxFileSizeBytes;
    private final int maxBackupFiles;

    private OutputStream outputStream;
    private long currentFileBytes;
    private boolean closed;

    private AuditLogger(boolean enabled, Path logPath, long maxFileSizeBytes, int maxBackupFiles) throws IOException {
        this.enabled = enabled;
        this.logPath = logPath;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxBackupFiles = maxBackupFiles;
        if (enabled) {
            openAppendStream();
        }
    }

    /**
     * Returns a no-op logger that performs no disk I/O (used when logging is disabled or in unit tests).
     */
    public static AuditLogger noop() {
        try {
            return new AuditLogger(false, null, 0L, 0);
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected I/O error constructing noop AuditLogger", e);
        }
    }

    /**
     * Creates an {@link AuditLogger} from {@link ServerConfig.LoggingConfig}.
     * Returns {@link #noop()} if {@code config.enabled()} is {@code false}.
     */
    public static AuditLogger fromConfig(ServerConfig.LoggingConfig config) throws IOException {
        Objects.requireNonNull(config, "logging config must not be null");
        if (!config.enabled()) {
            return noop();
        }
        return open(Path.of(config.filePath()), config.maxFileSizeBytes(), config.maxBackupFiles());
    }

    /**
     * Opens an appending JSONL audit logger at {@code logPath} with explicit byte-level rotation threshold.
     */
    public static AuditLogger open(Path logPath, long maxFileSizeBytes, int maxBackupFiles) throws IOException {
        Objects.requireNonNull(logPath, "logPath must not be null");
        if (maxFileSizeBytes < 1L) {
            throw new IllegalArgumentException("maxFileSizeBytes must be >= 1, got " + maxFileSizeBytes);
        }
        if (maxBackupFiles < 0) {
            throw new IllegalArgumentException("maxBackupFiles must be >= 0, got " + maxBackupFiles);
        }
        return new AuditLogger(true, logPath, maxFileSizeBytes, maxBackupFiles);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Path logPath() {
        return logPath;
    }

    public void logServerStart(ResolvedAddresses addresses, String aiServerUrl, AiStatus aiStatus) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "SERVER_START");
        node.put("version", BuildInfo.version());
        node.put("bindHost", addresses.bindHost());
        node.put("port", addresses.port());
        putNullable(node, "localLanIp", addresses.localLanIp());
        putNullable(node, "tailscaleIp", addresses.tailscaleIp());
        putNullable(node, "publicIp", addresses.publicIp());
        putNullable(node, "aiServer", aiServerUrl);
        node.put("aiStatus", aiStatus != null ? aiStatus.summaryText() : "UNKNOWN");
        emit(node);
    }

    public void logServerStop(String reason, long uptimeMs) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "SERVER_STOP");
        node.put("reason", reason != null ? reason : "UNKNOWN");
        node.put("uptimeMs", Math.max(0L, uptimeMs));
        emit(node);
    }

    public void logHttpAccess(String clientIp, String method, String path, int status, long durationMs) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "HTTP_ACCESS");
        node.put("ip", safeIp(clientIp));
        node.put("method", method != null ? method : "UNKNOWN");
        node.put("path", path != null ? path : "/");
        node.put("status", status);
        node.put("durationMs", Math.max(0L, durationMs));
        emit(node);
    }

    public void logWsConnect(String sessionId, String clientIp) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "WS_CONNECT");
        node.put("sessionId", sessionId);
        node.put("ip", safeIp(clientIp));
        emit(node);
    }

    public void logLobbyJoin(String sessionId, String clientIp, String username) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "LOBBY_JOIN");
        node.put("sessionId", sessionId);
        node.put("ip", safeIp(clientIp));
        node.put("username", username);
        emit(node);
    }

    public void logQueueJoin(String sessionId, String clientIp, String username, int position) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "QUEUE_JOIN");
        node.put("sessionId", sessionId);
        node.put("ip", safeIp(clientIp));
        node.put("username", username);
        node.put("position", position);
        emit(node);
    }

    public void logQueueCancel(String sessionId, String clientIp, String username) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "QUEUE_CANCEL");
        node.put("sessionId", sessionId);
        node.put("ip", safeIp(clientIp));
        putNullable(node, "username", username);
        emit(node);
    }

    public void logMatchStart(
        String matchId,
        String mode,
        String playerX,
        String ipX,
        String playerO,
        String ipO,
        String firstTurn,
        boolean rematch
    ) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "MATCH_START");
        node.put("matchId", matchId);
        node.put("mode", mode);
        node.put("playerX", playerX);
        putNullable(node, "ipX", ipX);
        node.put("playerO", playerO);
        putNullable(node, "ipO", ipO);
        node.put("firstTurn", firstTurn);
        node.put("rematch", rematch);
        emit(node);
    }

    public void logMatchEnd(
        String matchId,
        String mode,
        String status,
        String winner,
        String drawReason,
        int totalPlies,
        long durationMs,
        String reason
    ) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "MATCH_END");
        node.put("matchId", matchId);
        node.put("mode", mode);
        node.put("status", status);
        putNullable(node, "winner", winner);
        node.put("drawReason", drawReason);
        node.put("totalPlies", totalPlies);
        node.put("durationMs", Math.max(0L, durationMs));
        node.put("reason", reason);
        emit(node);
    }

    public void logWsDisconnect(
        String sessionId,
        String clientIp,
        String username,
        long durationMs,
        Integer closeStatus
    ) {
        if (!enabled) {
            return;
        }
        ObjectNode node = baseEvent("INFO", "WS_DISCONNECT");
        node.put("sessionId", sessionId);
        node.put("ip", safeIp(clientIp));
        putNullable(node, "username", username);
        node.put("durationMs", Math.max(0L, durationMs));
        if (closeStatus != null) {
            node.put("closeStatus", closeStatus);
        } else {
            node.putNull("closeStatus");
        }
        emit(node);
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeStreamQuietly();
    }

    private ObjectNode baseEvent(String level, String event) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ts", OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString());
        node.put("level", level);
        node.put("event", event);
        return node;
    }

    private synchronized void emit(ObjectNode node) {
        if (!enabled || closed) {
            return;
        }
        try {
            byte[] lineBytes = (MAPPER.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
            if (currentFileBytes > 0L && (currentFileBytes + lineBytes.length) > maxFileSizeBytes) {
                rotateFiles();
            }
            if (outputStream == null) {
                openAppendStream();
            }
            outputStream.write(lineBytes);
            outputStream.flush();
            currentFileBytes += lineBytes.length;
        } catch (IOException e) {
            LOG.warn("Failed to write audit log entry to {}: {}", logPath, e.getMessage());
        }
    }

    private void openAppendStream() throws IOException {
        Path parent = logPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        currentFileBytes = Files.exists(logPath) ? Files.size(logPath) : 0L;
        outputStream = Files.newOutputStream(
            logPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private void rotateFiles() throws IOException {
        closeStreamQuietly();

        if (maxBackupFiles == 0) {
            Files.deleteIfExists(logPath);
        } else {
            // Delete oldest allowed backup, then shift existing backups upward from oldest to newest.
            Files.deleteIfExists(backupPath(maxBackupFiles));
            for (int i = maxBackupFiles - 1; i >= 1; i--) {
                Path src = backupPath(i);
                if (Files.exists(src)) {
                    Files.move(src, backupPath(i + 1), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.exists(logPath)) {
                Files.move(logPath, backupPath(1), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        openAppendStream();
    }

    private Path backupPath(int index) {
        return logPath.resolveSibling(logPath.getFileName().toString() + "." + index);
    }

    private void closeStreamQuietly() {
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException ignored) {
                // Best effort on close.
            } finally {
                outputStream = null;
            }
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String safeIp(String ip) {
        return (ip == null || ip.isBlank()) ? "unknown" : ip;
    }
}
