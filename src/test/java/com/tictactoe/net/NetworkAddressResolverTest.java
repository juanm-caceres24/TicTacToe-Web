package com.tictactoe.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.tictactoe.ai.AiStatus;
import com.tictactoe.config.ServerConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class NetworkAddressResolverTest {

    private static Inet4Address ipv4(String literal) throws IOException {
        return (Inet4Address) InetAddress.getByName(literal);
    }

    @Test
    void classifiesTailscaleCgnatRangeAccurately() throws IOException {
        assertFalse(NetworkAddressResolver.isTailscaleIpv4(ipv4("100.63.255.255")));
        assertTrue(NetworkAddressResolver.isTailscaleIpv4(ipv4("100.64.0.0")));
        assertTrue(NetworkAddressResolver.isTailscaleIpv4(ipv4("100.88.12.5")));
        assertTrue(NetworkAddressResolver.isTailscaleIpv4(ipv4("100.127.255.255")));
        assertFalse(NetworkAddressResolver.isTailscaleIpv4(ipv4("100.128.0.0")));
        assertFalse(NetworkAddressResolver.isTailscaleIpv4(ipv4("192.168.1.10")));
    }

    @Test
    void selectsLanAndTailscaleIgnoringVirtualInterfaces() throws IOException {
        List<NetworkAddressResolver.InterfaceAddress> interfaces = List.of(
            new NetworkAddressResolver.InterfaceAddress("lo", ipv4("127.0.0.1")),
            new NetworkAddressResolver.InterfaceAddress("docker0", ipv4("172.17.0.1")),
            new NetworkAddressResolver.InterfaceAddress("br-98ab12", ipv4("192.168.99.1")),
            new NetworkAddressResolver.InterfaceAddress("veth3a4f", ipv4("10.0.5.2")),
            new NetworkAddressResolver.InterfaceAddress("eth0", ipv4("10.0.0.15")),
            new NetworkAddressResolver.InterfaceAddress("wlan0", ipv4("192.168.1.42")),
            new NetworkAddressResolver.InterfaceAddress("tailscale0", ipv4("100.91.44.7"))
        );

        assertEquals("192.168.1.42", NetworkAddressResolver.selectLocalLanIp(interfaces));
        assertEquals("100.91.44.7", NetworkAddressResolver.selectTailscaleIp(interfaces));

        // Without 192.168.x.x, falls back to 10.x.x.x on real NIC rather than docker0.
        List<NetworkAddressResolver.InterfaceAddress> onlyTen = List.of(
            new NetworkAddressResolver.InterfaceAddress("docker0", ipv4("172.17.0.1")),
            new NetworkAddressResolver.InterfaceAddress("eth0", ipv4("10.0.0.15"))
        );
        assertEquals("10.0.0.15", NetworkAddressResolver.selectLocalLanIp(onlyTen));
        assertNull(NetworkAddressResolver.selectTailscaleIp(onlyTen));
    }

    @Test
    void validatesIpLiteralsAndRejectsHtmlNoise() {
        assertTrue(NetworkAddressResolver.isValidIpLiteral("181.45.12.90"));
        assertTrue(NetworkAddressResolver.isValidIpLiteral("2001:db8::1"));
        assertFalse(NetworkAddressResolver.isValidIpLiteral("999.1.2.3"));
        assertFalse(NetworkAddressResolver.isValidIpLiteral("<html><body>502</body></html>"));
        assertFalse(NetworkAddressResolver.isValidIpLiteral(""));
        assertFalse(NetworkAddressResolver.isValidIpLiteral(null));
    }

    @Test
    void fetchPublicIpHandlesValidResponseErrorsInvalidBodyTimeoutAndDisabled() throws IOException {
        HttpServer mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.setExecutor(Executors.newCachedThreadPool());
        mock.createContext("/ok", exchange -> sendText(exchange, 200, "181.45.12.90\n"));
        mock.createContext("/error", exchange -> sendText(exchange, 500, "internal error"));
        mock.createContext("/html", exchange -> sendText(exchange, 200, "<html>not an ip</html>"));
        mock.createContext("/slow", exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sendText(exchange, 200, "181.45.12.90");
        });
        mock.start();

        try {
            String base = "http://127.0.0.1:" + mock.getAddress().getPort();

            assertEquals(
                "181.45.12.90",
                NetworkAddressResolver.fetchPublicIp(new ServerConfig.PublicIpLookupConfig(true, base + "/ok", 1000))
            );
            assertNull(
                NetworkAddressResolver.fetchPublicIp(new ServerConfig.PublicIpLookupConfig(true, base + "/error", 1000))
            );
            assertNull(
                NetworkAddressResolver.fetchPublicIp(new ServerConfig.PublicIpLookupConfig(true, base + "/html", 1000))
            );
            assertNull(
                NetworkAddressResolver.fetchPublicIp(new ServerConfig.PublicIpLookupConfig(true, base + "/slow", 150))
            );
            assertNull(
                NetworkAddressResolver.fetchPublicIp(new ServerConfig.PublicIpLookupConfig(false, base + "/ok", 1000))
            );
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void formatsStartupBannerWithAvailableAndUnavailableAddresses() {
        ResolvedAddresses full = new ResolvedAddresses("0.0.0.0", 3000, "192.168.1.42", "100.88.12.5", "181.45.12.90");
        String banner = full.formatStartupBanner("http://127.0.0.1:8080", AiStatus.ready(9, 41, 3, 23081));

        assertTrue(banner.contains("http://192.168.1.42:3000"));
        assertTrue(banner.contains("http://100.88.12.5:3000"));
        assertTrue(banner.contains("http://181.45.12.90:3000"));
        assertTrue(banner.contains("ONLINE | 9->41"));

        ResolvedAddresses partial = new ResolvedAddresses("0.0.0.0", 3000, "192.168.1.42", null, null);
        String partialBanner = partial.formatStartupBanner("http://127.0.0.1:8080", AiStatus.offline("connection refused"));
        assertTrue(partialBanner.contains("Tailscale   : unavailable"));
        assertTrue(partialBanner.contains("Public      : unavailable"));
        assertTrue(partialBanner.contains("OFFLINE | connection refused"));
    }

    private static void sendText(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
