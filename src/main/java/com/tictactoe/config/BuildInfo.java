package com.tictactoe.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Exposes build metadata filtered from {@code pom.xml} into {@code /version.properties}.
 */
public final class BuildInfo {

    private static final String VERSION = loadVersion();

    private BuildInfo() {
        // Utility class
    }

    /**
     * Returns the Maven project version compiled into this artifact.
     */
    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String val = props.getProperty("version", "").trim();
                if (!val.isEmpty() && !val.startsWith("${")) {
                    return val;
                }
            }
        } catch (IOException ignored) {
            // Fall back to unknown below.
        }
        return "dev";
    }
}
