package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Service for validating and filtering URLs before security scans.
 * Prevents scanning of internal resources, localhost, and blacklisted domains.
 */
@Slf4j
@Service
public class UrlValidationService {

    @Value("${zap.scan.url.allowLocalhost:false}")
    private boolean allowLocalhost;

    @Value("${zap.scan.url.allowPrivateNetworks:false}")
    private boolean allowPrivateNetworks;

    @Value("${zap.scan.url.validation.enabled:true}")
    private boolean validationEnabled = true;

    @Value("#{'${zap.scan.url.whitelist:}'.split(',')}")
    private List<String> whitelist;

    @Value("#{'${zap.scan.url.blacklist:localhost,127.0.0.1,0.0.0.0,169.254.0.0/16,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}'.split(',')}")
    private List<String> blacklist;

    private static final Pattern IPV4_LITERAL_PATTERN = Pattern.compile(
        "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$"
    );

    /**
     * Validates a URL for security scanning.
     * Checks format, reachability, and security policies.
     *
     * @param urlString The URL to validate
     * @throws IllegalArgumentException if URL is invalid or not allowed
     */
    public void validateUrl(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty");
        }

        if (!validationEnabled) {
            log.warn("URL validation is disabled. Skipping checks for target: {}", urlString);
            return;
        }

        URL url;
        try {
            url = URI.create(urlString).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL format: " + urlString, e);
        }

        // Validate protocol
        String protocol = url.getProtocol().toLowerCase();
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IllegalArgumentException("Only HTTP and HTTPS protocols are allowed, got: " + protocol);
        }

        String host = normalizeHost(url.getHost());
        if (host.isEmpty()) {
            throw new IllegalArgumentException("URL must include a valid host");
        }

        // If whitelist is configured, host must be whitelisted.
        // Whitelisted hosts still go through blacklist and network safety checks.
        if (hasConfiguredEntries(whitelist)) {
            boolean whitelisted = whitelist.stream()
                .filter(pattern -> pattern != null && !pattern.trim().isEmpty())
                .anyMatch(pattern -> matchesPattern(host, pattern.trim()));

            if (!whitelisted) {
                log.warn("URL host '{}' is not in whitelist", host);
                throw new IllegalArgumentException(
                    "URL host '" + host + "' is not in the allowed whitelist. Contact administrator to whitelist this domain."
                );
            }
        }

        // Check for localhost before blacklist
        if (!allowLocalhost && isLocalhost(host)) {
            throw new IllegalArgumentException(
                "Scanning localhost is not allowed. Set zap.scan.url.allowLocalhost=true to enable."
            );
        }

        // Check blacklist for hostname (but not for localhost if localhost is allowed)
        if (!(allowLocalhost && isLocalhost(host)) && isBlacklistedHost(host)) {
            throw new IllegalArgumentException("URL host '" + host + "' is blacklisted");
        }

        // Resolve all hostname records and validate each address.
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new UnknownHostException("No DNS records found for host: " + host);
            }
        } catch (UnknownHostException e) {
            log.warn("Unable to resolve hostname: {}", host, e);
            throw new IllegalArgumentException("Unable to resolve hostname: " + host, e);
        }

        for (InetAddress address : addresses) {
            // Explicitly reject unsafe non-routable address classes.
            if (address.isAnyLocalAddress()) {
                throw new IllegalArgumentException("Unspecified addresses are not allowed");
            }

            if (address.isMulticastAddress()) {
                throw new IllegalArgumentException("Multicast addresses are not allowed");
            }

            // Check for loopback
            if (!allowLocalhost && address.isLoopbackAddress()) {
                throw new IllegalArgumentException("Loopback addresses are not allowed");
            }

            // Check for link-local
            if (address.isLinkLocalAddress()) {
                throw new IllegalArgumentException("Link-local addresses are not allowed");
            }

            // Check for private/internal IP ranges
            if (!allowPrivateNetworks && isPrivateNetwork(address)) {
                throw new IllegalArgumentException(
                    "Scanning private network addresses is not allowed. Set zap.scan.url.allowPrivateNetworks=true to enable."
                );
            }

            // Apply blacklist checks to each resolved IP as an extra defense.
            String ipAddress = normalizeHost(address.getHostAddress());
            if (!(allowLocalhost && address.isLoopbackAddress()) && isBlacklistedHost(ipAddress)) {
                throw new IllegalArgumentException("Resolved address '" + ipAddress + "' is blacklisted");
            }
        }

        log.info("URL '{}' passed validation checks (resolved: {})", urlString, Arrays.toString(addresses));
    }

    /**
     * Check if host matches a pattern (supports wildcards).
     */
    private boolean matchesPattern(String host, String pattern) {
        if (pattern.equals("*")) {
            return true;
        }

        // Convert wildcard pattern to regex and escape all other regex metacharacters.
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if ("[](){}.+?$^|\\-".indexOf(c) >= 0) {
                regex.append("\\").append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append("$");

        return Pattern.matches(regex.toString(), host);
    }

    /**
     * Check if host is in the blacklist.
     */
    private boolean isBlacklistedHost(String host) {
        return blacklist.stream()
            .filter(entry -> entry != null && !entry.trim().isEmpty())
            .map(String::trim)
            .anyMatch(pattern -> matchesBlacklistEntry(host, pattern));
    }

    /**
     * Match host value against wildcard or CIDR blacklist entry.
     */
    private boolean matchesBlacklistEntry(String host, String pattern) {
        if (pattern.contains("/")) {
            InetAddress hostAddress = parseIpLiteral(host);
            return hostAddress != null && isInCidr(hostAddress, pattern);
        }
        return matchesPattern(host, pattern);
    }

    /**
     * Parse host string as IP literal; return null for hostnames/unparseable values.
     */
    private InetAddress parseIpLiteral(String value) {
        String normalized = normalizeHost(value);
        if (normalized.isEmpty()) {
            return null;
        }

        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        // Avoid DNS resolution for hostnames; CIDR matching applies only to IP literals.
        if (!IPV4_LITERAL_PATTERN.matcher(normalized).matches() && !normalized.contains(":")) {
            return null;
        }

        try {
            return InetAddress.getByName(normalized);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Return true when host address belongs to the configured CIDR network.
     */
    private boolean isInCidr(InetAddress hostAddress, String cidr) {
        String[] parts = cidr.split("/", 2);
        if (parts.length != 2) {
            return false;
        }

        InetAddress networkAddress = parseIpLiteral(parts[0]);
        if (networkAddress == null) {
            return false;
        }

        int prefixLength;
        try {
            prefixLength = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }

        byte[] hostBytes = hostAddress.getAddress();
        byte[] networkBytes = networkAddress.getAddress();
        if (hostBytes.length != networkBytes.length) {
            return false;
        }

        int maxPrefixLength = hostBytes.length * 8;
        if (prefixLength < 0 || prefixLength > maxPrefixLength) {
            return false;
        }

        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;

        for (int i = 0; i < fullBytes; i++) {
            if (hostBytes[i] != networkBytes[i]) {
                return false;
            }
        }

        if (remainingBits == 0) {
            return true;
        }

        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (hostBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
    }

    /**
     * Check if host is localhost variant.
     */
    private boolean isLocalhost(String host) {
        return host.equals("localhost")
            || host.equals("127.0.0.1")
            || host.equals("::1")
            || host.equals("0:0:0:0:0:0:0:1")
            || host.startsWith("127.")
            || host.startsWith("::ffff:127.")
            || host.endsWith(".localhost");
    }

    /**
     * Check if IP address is in private network range.
     */
    private boolean isPrivateNetwork(InetAddress address) {
        if (address.isSiteLocalAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();

        // Check private IPv4 ranges
        if (bytes.length == 4) {
            int firstOctet = bytes[0] & 0xFF;
            int secondOctet = bytes[1] & 0xFF;

            // 10.0.0.0/8
            if (firstOctet == 10) return true;

            // 172.16.0.0/12
            if (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31) return true;

            // 192.168.0.0/16
            if (firstOctet == 192 && secondOctet == 168) return true;

            // 169.254.0.0/16 (link-local)
            if (firstOctet == 169 && secondOctet == 254) return true;

            // 100.64.0.0/10 (carrier-grade NAT)
            if (firstOctet == 100 && secondOctet >= 64 && secondOctet <= 127) return true;

            // 198.18.0.0/15 (benchmarking)
            if (firstOctet == 198 && (secondOctet == 18 || secondOctet == 19)) return true;
        }

        // Check IPv6 unique-local addresses (fc00::/7)
        if (bytes.length == 16) {
            int firstByte = bytes[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) {
                return true;
            }
        }

        return false;
    }

    /**
     * Return true when list contains at least one non-empty configured value.
     */
    private boolean hasConfiguredEntries(List<String> entries) {
        return entries.stream().anyMatch(entry -> entry != null && !entry.trim().isEmpty());
    }

    /**
     * Normalize host text for comparisons (trim, lowercase, strip trailing dot).
     */
    private String normalizeHost(String host) {
        if (host == null) {
            return "";
        }

        String normalized = host.trim().toLowerCase();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Get validation configuration summary for logging/debugging.
     */
    public String getConfigurationSummary() {
        return String.format(
            "URL Validation Config: allowLocalhost=%s, allowPrivateNetworks=%s, whitelist=%s, blacklist=%s",
            allowLocalhost, allowPrivateNetworks, 
            whitelist.isEmpty() ? "[]" : whitelist,
            blacklist.isEmpty() ? "[]" : blacklist
        );
    }
}
