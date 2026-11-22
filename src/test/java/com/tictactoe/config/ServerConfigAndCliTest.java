package com.tictactoe.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigAndCliTest {

    @Test
    void loadsRepositoryServerJsonSuccessfully() throws IOException {
        ServerConfig config = ServerConfig.load(CliArgs.DEFAULT_CONFIG_PATH);

        assertEquals("0.0.0.0", config.host());
        assertEquals(3000, config.port());
        assertEquals(100, config.maxConcurrentMatches());
        assertEquals(2, config.minUsernameLength());
        assertEquals(20, config.maxUsernameLength());
        assertEquals(3, config.repetitionLimit());
        assertEquals(50, config.maxMovementPlies());

        assertEquals(65_536L, config.transport().maxHttpRequestBytes());
        assertEquals(8_192L, config.transport().maxWsMessageBytes());
        assertEquals(300, config.transport().wsIdleTimeoutSeconds());

        assertTrue(config.logging().enabled());
        assertEquals("logs/audit.log", config.logging().filePath());
        assertTrue(config.logging().logHttpRequests());
        assertFalse(config.logging().trustProxyHeaders());
        assertEquals(10, config.logging().maxFileSizeMb());
        assertEquals(5, config.logging().maxBackupFiles());

        assertTrue(config.publicIpLookup().enabled());
        assertEquals("https://api.ipify.org", config.publicIpLookup().url());
        assertEquals(2000, config.publicIpLookup().timeoutMs());

        assertEquals("127.0.0.1", config.aiServer().host());
        assertEquals(8080, config.aiServer().port());
        assertEquals("http://127.0.0.1:8080", config.aiServer().baseUrl());
        assertEquals(2000, config.aiServer().connectTimeoutMs());
        assertEquals(3000, config.aiServer().requestTimeoutMs());
        assertEquals(4, config.aiServer().workerThreads());
        assertEquals(ServerConfig.defaults(), config);
    }

    @Test
    void cliArgsParseOverridesAndApplyToConfig() {
        CliArgs parsed = CliArgs.parse(new String[] {
            "--config", "custom.json",
            "--host", "127.0.0.1",
            "--port", "4545",
            "--ai-host", "100.64.1.2",
            "--ai-port", "9090",
            "--log-file", "/tmp/custom-audit.log",
            "--no-public-ip"
        });

        assertFalse(parsed.helpRequested());
        assertEquals(Path.of("custom.json"), parsed.configPath());
        assertEquals("127.0.0.1", parsed.hostOverride());
        assertEquals(4545, parsed.portOverride());
        assertEquals("100.64.1.2", parsed.aiHostOverride());
        assertEquals(9090, parsed.aiPortOverride());
        assertEquals("/tmp/custom-audit.log", parsed.logFileOverride());
        assertEquals(Boolean.FALSE, parsed.publicIpEnabledOverride());

        ServerConfig merged = ServerConfig.defaults().withOverrides(parsed);
        assertEquals("127.0.0.1", merged.host());
        assertEquals(4545, merged.port());
        assertEquals("100.64.1.2", merged.aiServer().host());
        assertEquals(9090, merged.aiServer().port());
        assertEquals("/tmp/custom-audit.log", merged.logging().filePath());
        assertFalse(merged.publicIpLookup().enabled());

        // Empty overrides leave defaults intact.
        ServerConfig unchanged = ServerConfig.defaults().withOverrides(CliArgs.parse(new String[] {}));
        assertEquals(ServerConfig.defaults(), unchanged);
    }

    @Test
    void cliArgsHandleHelpBoundariesAndRejectBlankOrInvalidInput() {
        assertTrue(CliArgs.parse(new String[] {"-h"}).helpRequested());
        assertTrue(CliArgs.parse(new String[] {"--help"}).helpRequested());
        assertTrue(CliArgs.usageText().contains("./scripts/run.sh"));
        assertTrue(CliArgs.usageText().contains("--ai-port"));

        assertNull(CliArgs.parse(new String[] {}).portOverride());
        assertEquals(0, CliArgs.parse(new String[] {"--port", "0"}).portOverride());
        assertEquals(65535, CliArgs.parse(new String[] {"--port", "65535"}).portOverride());
        assertEquals(1, CliArgs.parse(new String[] {"--ai-port", "1"}).aiPortOverride());
        assertEquals(65535, CliArgs.parse(new String[] {"--ai-port", "65535"}).aiPortOverride());

        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--config", "   "}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--log-file", "   "}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--log-file"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--host", "   "}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--ai-host", ""}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--port", "abc"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--port", "-1"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--port", "70000"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--ai-port", "0"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--ai-port", "65536"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--config"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--host"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--port"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--ai-host"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--ai-port"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--unknown-flag"}));
    }

    @Test
    void validatesAllConfigRecordBoundaries() {
        ServerConfig.TransportConfig validTransport = ServerConfig.TransportConfig.defaults();
        ServerConfig.LoggingConfig validLogging = ServerConfig.LoggingConfig.defaults();
        ServerConfig.PublicIpLookupConfig validIp = ServerConfig.PublicIpLookupConfig.defaults();
        ServerConfig.AiServerConfig validAi = ServerConfig.AiServerConfig.defaults();

        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.TransportConfig(512, 8192, 60));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.TransportConfig(65536, 256, 60));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.TransportConfig(65536, 8192, 5));

        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.LoggingConfig(true, "  ", true, false, 10, 5));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.LoggingConfig(true, "logs/a.log", true, false, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.LoggingConfig(true, "logs/a.log", true, false, 10, -1));

        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.PublicIpLookupConfig(true, "ftp://ip.test", 1000));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.PublicIpLookupConfig(true, "http:///nohost", 1000));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.PublicIpLookupConfig(true, "https://api.ipify.org", 99));

        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.AiServerConfig(" ", 8080, 1000, 1000, 4));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.AiServerConfig("127.0.0.1", 0, 1000, 1000, 4));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.AiServerConfig("127.0.0.1", 65536, 1000, 1000, 4));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.AiServerConfig("127.0.0.1", 8080, 50, 1000, 4));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.AiServerConfig("127.0.0.1", 8080, 1000, 50, 4));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.AiServerConfig("127.0.0.1", 8080, 1000, 1000, 0));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig.AiServerConfig("127.0.0.1", 8080, 1000, 1000, 65));

        assertThrows(IllegalArgumentException.class, () -> new ServerConfig("0.0.0.0", 3000, 10, 10, 5, 3, 50, validTransport, validLogging, validIp, validAi));
        assertThrows(IllegalArgumentException.class, () -> new ServerConfig("0.0.0.0", 3000, 0, 2, 20, 3, 50, validTransport, validLogging, validIp, validAi));
        assertThrows(NullPointerException.class, () -> new ServerConfig("0.0.0.0", 3000, 10, 2, 20, 3, 50, null, validLogging, validIp, validAi));
        assertThrows(NullPointerException.class, () -> new ServerConfig("0.0.0.0", 3000, 10, 2, 20, 3, 50, validTransport, null, validIp, validAi));
    }

    @Test
    void configLoadRejectsMissingMalformedAndUnknownFieldFiles(@TempDir Path tempDir) throws IOException {
        Path missing = tempDir.resolve("does-not-exist.json");
        assertThrows(IOException.class, () -> ServerConfig.load(missing));

        Path malformed = tempDir.resolve("broken.json");
        Files.writeString(malformed, "{ broken json");
        assertThrows(IOException.class, () -> ServerConfig.load(malformed));

        Path typoFile = tempDir.resolve("typo.json");
        Files.writeString(typoFile, """
            {
              "host": "0.0.0.0",
              "port": 3000,
              "unknownKey": 123,
              "maxConcurrentMatches": 10,
              "minUsernameLength": 2,
              "maxUsernameLength": 20,
              "repetitionLimit": 3,
              "maxMovementPlies": 50,
              "transport": { "maxHttpRequestBytes": 65536, "maxWsMessageBytes": 8192, "wsIdleTimeoutSeconds": 300 },
              "publicIpLookup": { "enabled": false, "url": "https://api.ipify.org", "timeoutMs": 1000 },
              "aiServer": { "host": "127.0.0.1", "port": 8080, "connectTimeoutMs": 1000, "requestTimeoutMs": 1000, "workerThreads": 4 }
            }
            """);
        assertThrows(IOException.class, () -> ServerConfig.load(typoFile));

        Path missingSection = tempDir.resolve("missing-section.json");
        Files.writeString(missingSection, """
            {
              "host": "0.0.0.0",
              "port": 3000,
              "maxConcurrentMatches": 10,
              "minUsernameLength": 2,
              "maxUsernameLength": 20,
              "repetitionLimit": 3,
              "maxMovementPlies": 50
            }
            """);
        assertThrows(IOException.class, () -> ServerConfig.load(missingSection));
    }
}
