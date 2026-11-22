package com.tictactoe.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tictactoe.ai.AiStatus;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.net.ResolvedAddresses;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLoggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void createsFileAutomaticallyWritesValidJsonLinesAndAppendsAcrossRestarts(@TempDir Path tempDir)
        throws IOException {
        Path logFile = tempDir.resolve("nested/logs/audit.log");
        assertFalse(Files.exists(logFile));

        // First server run
        try (AuditLogger firstRun = AuditLogger.open(logFile, 1024 * 1024L, 3)) {
            ResolvedAddresses addrs = new ResolvedAddresses("0.0.0.0", 3000, "192.168.1.42", "100.88.12.5", "181.1.2.3");
            firstRun.logServerStart(addrs, "http://127.0.0.1:8080", AiStatus.ready(9, 41, 3, 23081));
            firstRun.logWsConnect("sess-1", "181.45.12.90");
            firstRun.logLobbyJoin("sess-1", "181.45.12.90", "Alice");
            firstRun.logServerStop("SHUTDOWN_HOOK", 1500L);
        }

        // Second server run appends to the same file without overwriting previous lines.
        try (AuditLogger secondRun = AuditLogger.open(logFile, 1024 * 1024L, 3)) {
            secondRun.logMatchStart("m-1", "VS_AI", "Alice", "181.45.12.90", "NeuralNetwork AI", null, "X", false);
            secondRun.logMatchEnd("m-1", "VS_AI", "X_WINS", "Alice", "NONE", 5, 4200L, "COMPLETED");
            secondRun.logWsDisconnect("sess-1", "181.45.12.90", "Alice", 9500L, 1000);
        }

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertEquals(7, lines.size());

        // Every line must be a valid single-line JSON object with a parseable ISO-8601 timestamp.
        String[] expectedEvents = {
            "SERVER_START", "WS_CONNECT", "LOBBY_JOIN", "SERVER_STOP",
            "MATCH_START", "MATCH_END", "WS_DISCONNECT"
        };
        for (int i = 0; i < lines.size(); i++) {
            JsonNode node = MAPPER.readTree(lines.get(i));
            assertEquals("INFO", node.get("level").asText());
            assertEquals(expectedEvents[i], node.get("event").asText());
            OffsetDateTime.parse(node.get("ts").asText());
        }

        JsonNode startNode = MAPPER.readTree(lines.get(0));
        assertEquals("192.168.1.42", startNode.get("localLanIp").asText());
        assertEquals("100.88.12.5", startNode.get("tailscaleIp").asText());

        JsonNode disconnectNode = MAPPER.readTree(lines.get(6));
        assertEquals("181.45.12.90", disconnectNode.get("ip").asText());
        assertEquals(1000, disconnectNode.get("closeStatus").asInt());
    }

    @Test
    void rotatesFilesBySizeUsingRollingShiftAndBoundsBackupCount(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("audit.log");
        // Small byte threshold (~120 bytes) so every 2nd entry triggers rotation.
        try (AuditLogger logger = AuditLogger.open(logFile, 140L, 2)) {
            for (int i = 1; i <= 5; i++) {
                logger.logWsConnect("session-" + i, "10.0.0." + i);
            }
        }

        Path backup1 = tempDir.resolve("audit.log.1");
        Path backup2 = tempDir.resolve("audit.log.2");
        Path backup3 = tempDir.resolve("audit.log.3");

        assertTrue(Files.isRegularFile(logFile));
        assertTrue(Files.isRegularFile(backup1));
        assertTrue(Files.isRegularFile(backup2));
        assertFalse(Files.exists(backup3), "Backups beyond maxBackupFiles=2 must be discarded");

        // Active audit.log holds the newest entry (session-5); .1 holds session-4; .2 holds session-3.
        assertEquals("session-5", MAPPER.readTree(Files.readAllLines(logFile).get(0)).get("sessionId").asText());
        assertEquals("session-4", MAPPER.readTree(Files.readAllLines(backup1).get(0)).get("sessionId").asText());
        assertEquals("session-3", MAPPER.readTree(Files.readAllLines(backup2).get(0)).get("sessionId").asText());
    }

    @Test
    void rotationPreservesPreExistingBackupGapWithoutOverwriting(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("audit.log");
        Path existingGapBackup = tempDir.resolve("audit.log.2");
        Files.writeString(existingGapBackup, "{\"marker\":\"old-backup-2\"}\n", StandardCharsets.UTF_8);

        try (AuditLogger logger = AuditLogger.open(logFile, 140L, 3)) {
            logger.logWsConnect("first", "192.168.1.10");
            logger.logWsConnect("second", "192.168.1.11");
        }

        // Pre-existing audit.log.2 shifted safely to audit.log.3; first moved to audit.log.1; second is in audit.log.
        assertTrue(Files.isRegularFile(tempDir.resolve("audit.log.3")));
        assertEquals("{\"marker\":\"old-backup-2\"}\n", Files.readString(tempDir.resolve("audit.log.3")));
        assertEquals("first", MAPPER.readTree(Files.readAllLines(tempDir.resolve("audit.log.1")).get(0)).get("sessionId").asText());
        assertEquals("second", MAPPER.readTree(Files.readAllLines(logFile).get(0)).get("sessionId").asText());
    }

    @Test
    void preventsLogInjectionByEscapingNewlinesInsideJsonValues(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("audit.log");
        try (AuditLogger logger = AuditLogger.open(logFile, 1024 * 1024L, 2)) {
            logger.logHttpAccess("181.1.2.3", "GET", "/foo\n{\"event\":\"FORGED\"}", 404, 2L);
        }

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), "Newline inside HTTP path must not split into a second log line");
        JsonNode parsed = MAPPER.readTree(lines.get(0));
        assertEquals("HTTP_ACCESS", parsed.get("event").asText());
        assertTrue(parsed.get("path").asText().contains("FORGED"));
    }

    @Test
    void disabledConfigCreatesNoopLoggerWithoutTouchingDisk(@TempDir Path tempDir) throws IOException {
        Path unused = tempDir.resolve("should-not-exist.log");
        ServerConfig.LoggingConfig disabled = new ServerConfig.LoggingConfig(false, unused.toString(), true, false, 10, 5);
        try (AuditLogger logger = AuditLogger.fromConfig(disabled)) {
            assertFalse(logger.isEnabled());
            logger.logWsConnect("s-1", "127.0.0.1");
        }
        assertFalse(Files.exists(unused));
    }

    @Test
    void rotationWithZeroBackupsRecreatesActiveFileWithoutCreatingBackupFiles(@TempDir Path tempDir)
        throws IOException {
        Path logFile = tempDir.resolve("audit.log");
        try (AuditLogger logger = AuditLogger.open(logFile, 140L, 0)) {
            logger.logWsConnect("first", "10.0.0.1");
            logger.logWsConnect("second", "10.0.0.2");
            logger.logWsConnect("third", "10.0.0.3");
        }

        assertTrue(Files.isRegularFile(logFile));
        assertFalse(Files.exists(tempDir.resolve("audit.log.0")));
        assertFalse(Files.exists(tempDir.resolve("audit.log.1")));
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals("third", MAPPER.readTree(lines.get(0)).get("sessionId").asText());
    }

    @Test
    void concurrentMultiThreadedWritesAndRotationsProduceOnlyValidJsonLines(@TempDir Path tempDir)
        throws Exception {
        Path logFile = tempDir.resolve("audit.log");
        int threads = 6;
        int writesPerThread = 25;

        try (AuditLogger logger = AuditLogger.open(logFile, 600L, 4)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(pool.submit(() -> {
                    try {
                        gate.await();
                        for (int i = 0; i < writesPerThread; i++) {
                            logger.logWsConnect("t" + tid + "-" + i, "10.0." + tid + "." + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
        }

        int totalValidLines = 0;
        List<Path> candidates = List.of(
            logFile,
            tempDir.resolve("audit.log.1"),
            tempDir.resolve("audit.log.2"),
            tempDir.resolve("audit.log.3"),
            tempDir.resolve("audit.log.4")
        );
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                JsonNode node = MAPPER.readTree(line);
                assertEquals("WS_CONNECT", node.get("event").asText());
                OffsetDateTime.parse(node.get("ts").asText());
                totalValidLines++;
            }
        }
        assertFalse(Files.exists(tempDir.resolve("audit.log.5")));
        assertTrue(totalValidLines > 0);
    }
}
