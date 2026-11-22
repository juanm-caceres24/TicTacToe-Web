package com.tictactoe.net;

import com.tictactoe.config.ServerConfig;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Discovers the server's Local LAN IP, Tailscale IP ({@code 100.64.0.0/10}),
 * and optional external Public IP for display in the startup console banner.
 */
public final class NetworkAddressResolver {

    /**
     * Lightweight representation of a discovered network interface IPv4 address.
     */
    public record InterfaceAddress(String interfaceName, Inet4Address address) {
        public InterfaceAddress {
            Objects.requireNonNull(interfaceName, "interfaceName must not be null");
            Objects.requireNonNull(address, "address must not be null");
        }
    }

    private static final String[] IGNORED_INTERFACE_PREFIXES = {
        "lo", "docker", "br-", "veth", "virbr", "cni", "flannel", "kube", "podman", "wg-docker"
    };

    private NetworkAddressResolver() {
        // Utility class
    }

    /**
     * Resolves LAN, Tailscale, and Public IP addresses for the given configuration and bound port.
     */
    public static ResolvedAddresses resolve(ServerConfig config, int activePort) {
        Objects.requireNonNull(config, "config must not be null");
        List<InterfaceAddress> interfaces = enumerateActiveIpv4Interfaces();
        String lanIp = selectLocalLanIp(interfaces);
        String tailscaleIp = selectTailscaleIp(interfaces);
        String publicIp = fetchPublicIp(config.publicIpLookup());
        return new ResolvedAddresses(config.host(), activePort, lanIp, tailscaleIp, publicIp);
    }

    /**
     * Returns true if {@code address} lies in the RFC 6598 CGNAT range used by Tailscale ({@code 100.64.0.0/10}).
     */
    public static boolean isTailscaleIpv4(Inet4Address address) {
        Objects.requireNonNull(address, "address must not be null");
        byte[] bytes = address.getAddress();
        int firstOctet = bytes[0] & 0xFF;
        int secondOctet = bytes[1] & 0xFF;
        return firstOctet == 100 && (secondOctet & 0xC0) == 64;
    }

    /**
     * Returns true if {@code address} is an RFC 1918 private LAN IPv4 address
     * ({@code 10.0.0.0/8}, {@code 172.16.0.0/12}, or {@code 192.168.0.0/16}).
     */
    public static boolean isPrivateLanIpv4(Inet4Address address) {
        Objects.requireNonNull(address, "address must not be null");
        byte[] bytes = address.getAddress();
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        if (b0 == 10) {
            return true;
        }
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {
            return true;
        }
        return b0 == 192 && b1 == 168;
    }

    /**
     * Returns true if an interface name corresponds to loopback or container/virtual bridge interfaces
     * that should not be advertised as the host LAN address.
     */
    public static boolean isIgnoredVirtualInterface(String interfaceName) {
        if (interfaceName == null || interfaceName.isBlank()) {
            return true;
        }
        String lower = interfaceName.toLowerCase(Locale.ROOT);
        for (String prefix : IGNORED_INTERFACE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Selects the best Tailscale IPv4 address from a list of active interface addresses.
     * Prefers interfaces explicitly named {@code tailscale*} or {@code ts*} in {@code 100.64.0.0/10}.
     */
    public static String selectTailscaleIp(List<InterfaceAddress> addresses) {
        Objects.requireNonNull(addresses, "addresses must not be null");
        String fallback = null;
        for (InterfaceAddress entry : addresses) {
            if (!isTailscaleIpv4(entry.address())) {
                continue;
            }
            String lower = entry.interfaceName().toLowerCase(Locale.ROOT);
            if (lower.startsWith("tailscale") || lower.startsWith("ts")) {
                return entry.address().getHostAddress();
            }
            if (fallback == null) {
                fallback = entry.address().getHostAddress();
            }
        }
        return fallback;
    }

    /**
     * Selects the best Local LAN IPv4 address from a list of active interface addresses.
     * Ignores loopback, Tailscale, link-local, and virtual container interfaces, preferring {@code 192.168.x.x}.
     */
    public static String selectLocalLanIp(List<InterfaceAddress> addresses) {
        Objects.requireNonNull(addresses, "addresses must not be null");
        String preferred192 = null;
        String otherPrivate = null;
        String otherNonLoopback = null;

        for (InterfaceAddress entry : addresses) {
            Inet4Address addr = entry.address();
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || isTailscaleIpv4(addr)) {
                continue;
            }
            if (isIgnoredVirtualInterface(entry.interfaceName())) {
                continue;
            }
            String hostIp = addr.getHostAddress();
            byte[] octets = addr.getAddress();
            if ((octets[0] & 0xFF) == 192 && (octets[1] & 0xFF) == 168) {
                if (preferred192 == null) {
                    preferred192 = hostIp;
                }
            } else if (isPrivateLanIpv4(addr)) {
                if (otherPrivate == null) {
                    otherPrivate = hostIp;
                }
            } else if (otherNonLoopback == null) {
                otherNonLoopback = hostIp;
            }
        }

        if (preferred192 != null) {
            return preferred192;
        }
        if (otherPrivate != null) {
            return otherPrivate;
        }
        return otherNonLoopback;
    }

    /**
     * Fetches the host's public IP address from the configured lookup URL with a strict timeout.
     * Returns {@code null} if lookup is disabled, fails, times out, or returns a non-IP payload.
     */
    public static String fetchPublicIp(ServerConfig.PublicIpLookupConfig lookupConfig) {
        Objects.requireNonNull(lookupConfig, "lookupConfig must not be null");
        if (!lookupConfig.enabled()) {
            return null;
        }

        Duration timeout = Duration.ofMillis(lookupConfig.timeoutMs());
        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(lookupConfig.url().trim()))
            .timeout(timeout)
            .header("Accept", "text/plain")
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null) {
                return null;
            }
            String candidate = response.body().trim();
            return isValidIpLiteral(candidate) ? candidate : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Validates that a plain-text string is a clean IPv4 or IPv6 literal (not an HTML error page).
     */
    public static boolean isValidIpLiteral(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > 45) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean hexOrSep = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F')
                || c == '.'
                || c == ':';
            if (!hexOrSep) {
                return false;
            }
        }
        if (candidate.contains(".") && !candidate.contains(":")) {
            return isValidIpv4Literal(candidate);
        }
        if (candidate.contains(":") && !candidate.contains(".")) {
            return isValidIpv6Literal(candidate);
        }
        return false;
    }

    private static boolean isValidIpv4Literal(String candidate) {
        String[] parts = candidate.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidIpv6Literal(String candidate) {
        if (candidate.contains(":::")) {
            return false;
        }
        int doubleColonIdx = candidate.indexOf("::");
        if (doubleColonIdx != -1) {
            // At most one "::" compression is valid in RFC 4291.
            if (candidate.indexOf("::", doubleColonIdx + 2) != -1) {
                return false;
            }
            String left = candidate.substring(0, doubleColonIdx);
            String right = candidate.substring(doubleColonIdx + 2);
            int leftGroups = countValidHexGroups(left);
            int rightGroups = countValidHexGroups(right);
            if (leftGroups < 0 || rightGroups < 0) {
                return false;
            }
            return (leftGroups + rightGroups) < 8;
        }
        if (candidate.startsWith(":") || candidate.endsWith(":")) {
            return false;
        }
        return countValidHexGroups(candidate) == 8;
    }

    private static int countValidHexGroups(String segment) {
        if (segment.isEmpty()) {
            return 0;
        }
        if (segment.startsWith(":") || segment.endsWith(":")) {
            return -1;
        }
        String[] groups = segment.split(":", -1);
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 4) {
                return -1;
            }
            for (int i = 0; i < group.length(); i++) {
                char c = group.charAt(i);
                boolean isHex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
                if (!isHex) {
                    return -1;
                }
            }
        }
        return groups.length;
    }

    private static List<InterfaceAddress> enumerateActiveIpv4Interfaces() {
        List<InterfaceAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return result;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address ipv4) {
                        result.add(new InterfaceAddress(nif.getName(), ipv4));
                    }
                }
            }
        } catch (SocketException ignored) {
            // Return whatever addresses were collected so far.
        }
        return result;
    }
}
