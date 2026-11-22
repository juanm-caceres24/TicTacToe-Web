package com.tictactoe.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Parsed command-line arguments for overriding key {@link ServerConfig} settings at startup.
 */
public record CliArgs(
    boolean helpRequested,
    Path configPath,
    String hostOverride,
    Integer portOverride,
    String aiHostOverride,
    Integer aiPortOverride,
    Boolean publicIpEnabledOverride,
    String logFileOverride
) {

    public static final Path DEFAULT_CONFIG_PATH = Path.of("configs", "server.json");

    /**
     * Parses CLI arguments with strict numeric validation and clear error messages.
     *
     * @throws IllegalArgumentException if an unknown flag, missing argument value, or invalid port is provided
     */
    public static CliArgs parse(String[] args) {
        Objects.requireNonNull(args, "args must not be null");

        boolean help = false;
        Path configPath = DEFAULT_CONFIG_PATH;
        String host = null;
        Integer port = null;
        String aiHost = null;
        Integer aiPort = null;
        Boolean publicIpEnabled = null;
        String logFile = null;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "-h", "--help" -> help = true;
                case "--config" -> {
                    String value = requireNonBlankValue(args, i, flag);
                    configPath = Path.of(value);
                    i++;
                }
                case "--host" -> {
                    host = requireNonBlankValue(args, i, flag);
                    i++;
                }
                case "--port" -> {
                    port = parsePort(requireValue(args, i, flag), flag, 0, 65535);
                    i++;
                }
                case "--ai-host" -> {
                    aiHost = requireNonBlankValue(args, i, flag);
                    i++;
                }
                case "--ai-port" -> {
                    aiPort = parsePort(requireValue(args, i, flag), flag, 1, 65535);
                    i++;
                }
                case "--no-public-ip" -> publicIpEnabled = Boolean.FALSE;
                case "--log-file" -> {
                    logFile = requireNonBlankValue(args, i, flag);
                    i++;
                }
                default -> throw new IllegalArgumentException(
                    "Unknown argument '" + flag + "'. Use --help for usage."
                );
            }
        }

        return new CliArgs(help, configPath, host, port, aiHost, aiPort, publicIpEnabled, logFile);
    }

    /**
     * Returns formatted CLI usage instructions for {@code --help}.
     */
    public static String usageText() {
        return """
            Usage: ./scripts/run.sh [options]
                   java -jar target/tictactoe-web-<version>-shaded.jar [options]

            Options:
              --config <path>    Path to server JSON configuration (default: configs/server.json)
              --host <host>      Override server bind host (e.g. 0.0.0.0)
              --port <port>      Override server listening port [0..65535]
              --ai-host <host>   Override external NeuralNetwork server host
              --ai-port <port>   Override external NeuralNetwork server port [1..65535]
              --log-file <path>  Override JSONL audit log file path (default: logs/audit.log)
              --no-public-ip     Disable external public IP lookup at startup
              -h, --help         Show this help message and exit
            """;
    }

    private static String requireValue(String[] args, int flagIndex, String flag) {
        if (flagIndex + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for option " + flag);
        }
        return args[flagIndex + 1];
    }

    private static String requireNonBlankValue(String[] args, int flagIndex, String flag) {
        String value = requireValue(args, flagIndex, flag).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Option " + flag + " must not be blank");
        }
        return value;
    }

    private static int parsePort(String raw, String flag, int min, int max) {
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid numeric value for " + flag + ": '" + raw + "'", e
            );
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(
                "Value for " + flag + " out of range [" + min + ".." + max + "]: " + parsed
            );
        }
        return parsed;
    }
}
