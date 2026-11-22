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
        assertEquals(20, config.maxUsernameLength());
        assertEquals(3, config.repetitionLimit());
        assertEquals(50, config.maxMovementPlies());

        assertTrue(config.publicIpLookup().enabled());
        assertEquals("https://api.ipify.org", config.publicIpLookup().url());
        assertEquals(2000, config.publicIpLookup().timeoutMs());

        assertEquals("127.0.0.1", config.aiServer().host());
        assertEquals(8080, config.aiServer().port());
        assertEquals("http://127.0.0.1:8080", config.aiServer().baseUrl());
        assertEquals(2000, config.aiServer().connectTimeoutMs());
        assertEquals(3000, config.aiServer().requestTimeoutMs());
    }

    @Test
    void cliArgsParseOverridesAndApplyToConfig() {
        CliArgs parsed = CliArgs.parse(new String[] {
            "--config", "custom.json",
            "--host", "127.0.0.1",
            "--port", "4545",
            "--ai-host", "100.64.1.2",
            "--ai-port", "9090",
            "--no-public-ip"
        });

        assertFalse(parsed.helpRequested());
        assertEquals(Path.of("custom.json"), parsed.configPath());
        assertEquals("127.0.0.1", parsed.hostOverride());
        assertEquals(4545, parsed.portOverride());
        assertEquals("100.64.1.2", parsed.aiHostOverride());
        assertEquals(9090, parsed.aiPortOverride());
        assertEquals(Boolean.FALSE, parsed.publicIpEnabledOverride());

        ServerConfig merged = ServerConfig.defaults().withOverrides(parsed);
        assertEquals("127.0.0.1", merged.host());
        assertEquals(4545, merged.port());
        assertEquals("100.64.1.2", merged.aiServer().host());
        assertEquals(9090, merged.aiServer().port());
        assertFalse(merged.publicIpLookup().enabled());
    }

    @Test
    void cliArgsHandleHelpFlagsAndRejectInvalidInput() {
        CliArgs shortHelp = CliArgs.parse(new String[] {"-h"});
        assertTrue(shortHelp.helpRequested());

        CliArgs longHelp = CliArgs.parse(new String[] {"--help"});
        assertTrue(longHelp.helpRequested());
        assertTrue(CliArgs.usageText().contains("--ai-port"));

        assertNull(CliArgs.parse(new String[] {}).portOverride());

        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--port", "abc"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--port", "-1"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--port", "70000"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--ai-port", "0"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--host"}));
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse(new String[] {"--unknown-flag"}));
    }

    @Test
    void configLoadRejectsMissingFilesAndUnknownFields(@TempDir Path tempDir) throws IOException {
        Path missing = tempDir.resolve("does-not-exist.json");
        assertThrows(IOException.class, () -> ServerConfig.load(missing));

        Path typoFile = tempDir.resolve("typo.json");
        Files.writeString(typoFile, """
            {
              "host": "0.0.0.0",
              "port": 3000,
              "unknownKey": 123,
              "maxConcurrentMatches": 10,
              "maxUsernameLength": 20,
              "repetitionLimit": 3,
              "maxMovementPlies": 50,
              "publicIpLookup": { "enabled": false, "url": "https://api.ipify.org", "timeoutMs": 1000 },
              "aiServer": { "host": "127.0.0.1", "port": 8080, "connectTimeoutMs": 1000, "requestTimeoutMs": 1000 }
            }
            """);
        assertThrows(IOException.class, () -> ServerConfig.load(typoFile));

        Path badTimeout = tempDir.resolve("bad-timeout.json");
        Files.writeString(badTimeout, """
            {
              "host": "0.0.0.0",
              "port": 3000,
              "maxConcurrentMatches": 10,
              "maxUsernameLength": 20,
              "repetitionLimit": 3,
              "maxMovementPlies": 50,
              "publicIpLookup": { "enabled": false, "url": "https://api.ipify.org", "timeoutMs": 10 },
              "aiServer": { "host": "127.0.0.1", "port": 8080, "connectTimeoutMs": 1000, "requestTimeoutMs": 1000 }
            }
            """);
        assertThrows(IOException.class, () -> ServerConfig.load(badTimeout));
    }
}
