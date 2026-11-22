package com.tictactoe;

import com.tictactoe.ai.AiStatus;
import com.tictactoe.config.CliArgs;
import com.tictactoe.config.ServerConfig;
import com.tictactoe.net.NetworkAddressResolver;
import com.tictactoe.net.ResolvedAddresses;
import com.tictactoe.web.WebServer;
import java.io.IOException;

/**
 * Main entry point for the standalone TicTacToe-Web server.
 */
public final class Main {

    private Main() {
        // Entry point class
    }

    public static void main(String[] args) {
        CliArgs cliArgs;
        try {
            cliArgs = CliArgs.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.print(CliArgs.usageText());
            System.exit(1);
            return;
        }

        if (cliArgs.helpRequested()) {
            System.out.print(CliArgs.usageText());
            return;
        }

        ServerConfig config;
        try {
            config = ServerConfig.load(cliArgs.configPath()).withOverrides(cliArgs);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        WebServer server = new WebServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down TicTacToe-Web server...");
            server.close();
        }, "tictactoe-shutdown"));

        int activePort;
        try {
            activePort = server.start();
        } catch (RuntimeException e) {
            System.err.println("Failed to start server on " + config.host() + ":" + config.port() + ": " + e.getMessage());
            server.close();
            System.exit(1);
            return;
        }

        ResolvedAddresses addresses = NetworkAddressResolver.resolve(config, activePort);
        AiStatus aiStatus = server.aiClient().checkStatus();
        System.out.println(addresses.formatStartupBanner(config.aiServer().baseUrl(), aiStatus));
    }
}
