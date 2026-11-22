package com.tictactoe.net;

import com.tictactoe.ai.AiStatus;

/**
 * Resolved network addresses displayed at server startup (LAN, Tailscale, Public).
 *
 * @param bindHost    configured bind address (e.g. {@code 0.0.0.0})
 * @param port        active listening port
 * @param localLanIp  private LAN IPv4 address, or {@code null} if unavailable
 * @param tailscaleIp Tailscale CGNAT IPv4 address ({@code 100.64.0.0/10}), or {@code null} if unavailable
 * @param publicIp    public internet IP address, or {@code null} if disabled/unavailable
 */
public record ResolvedAddresses(
    String bindHost,
    int port,
    String localLanIp,
    String tailscaleIp,
    String publicIp
) {

    /**
     * Formats the startup console banner showing all reachable URLs and optional AI server status.
     */
    public String formatStartupBanner(String aiServerUrl, AiStatus aiStatus) {
        StringBuilder sb = new StringBuilder();
        sb.append("==========================================================\n");
        sb.append("  TicTacToe-Web Server listening on ").append(bindHost).append(':').append(port).append('\n');
        sb.append("----------------------------------------------------------\n");
        sb.append("  Local (LAN) : ").append(formatUrl(localLanIp)).append('\n');
        sb.append("  Tailscale   : ").append(formatUrl(tailscaleIp)).append('\n');
        sb.append("  Public      : ").append(formatUrl(publicIp)).append('\n');
        if (aiServerUrl != null) {
            String statusText = aiStatus != null ? aiStatus.summaryText() : "UNKNOWN";
            sb.append("  AI Server   : ").append(aiServerUrl).append(" [").append(statusText).append("]\n");
        }
        sb.append("==========================================================");
        return sb.toString();
    }

    private String formatUrl(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unavailable";
        }
        if (ip.contains(":")) {
            return "http://[" + ip + "]:" + port;
        }
        return "http://" + ip + ":" + port;
    }
}
