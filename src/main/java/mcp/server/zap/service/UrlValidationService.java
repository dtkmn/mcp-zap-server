package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.*;
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

    @Value("#{'${zap.scan.url.whitelist:}'.split(',')}")
    private List<String> whitelist;

    @Value("#{'${zap.scan.url.blacklist:localhost,127.0.0.1,0.0.0.0,169.254.0.0/16,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}'.split(',')}")
    private List<String> blacklist;

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

        String host = url.getHost().toLowerCase();

        // Check whitelist first (if configured, whitelist takes precedence)
        if (!whitelist.isEmpty() && !whitelist.get(0).isEmpty()) {
            boolean whitelisted = whitelist.stream()
                .anyMatch(pattern -> matchesPattern(host, pattern.trim()));
            
            if (!whitelisted) {
                log.warn("URL host '{}' is not in whitelist", host);
                throw new IllegalArgumentException(
                    "URL host '" + host + "' is not in the allowed whitelist. Contact administrator to whitelist this domain."
                );
            }
            // If whitelisted, skip other checks
            log.info("URL '{}' is whitelisted for scanning", urlString);
            return;
        }

        // Check for localhost before blacklist
        if (!allowLocalhost && isLocalhost(host)) {
            throw new IllegalArgumentException(
                "Scanning localhost is not allowed. Set zap.scan.url.allowLocalhost=true to enable."
            );
        }

        // Check blacklist (but not for localhost if localhost is allowed)
        if (!(allowLocalhost && isLocalhost(host)) && isBlacklisted(host)) {
            throw new IllegalArgumentException("URL host '" + host + "' is blacklisted");
        }

        // Resolve hostname to IP and check private networks
        try {
            InetAddress address = InetAddress.getByName(host);
            
            // Check for private/internal IP ranges
            if (!allowPrivateNetworks && isPrivateNetwork(address)) {
                throw new IllegalArgumentException(
                    "Scanning private network addresses is not allowed. Set zap.scan.url.allowPrivateNetworks=true to enable."
                );
            }

            // Check for loopback
            if (!allowLocalhost && address.isLoopbackAddress()) {
                throw new IllegalArgumentException("Loopback addresses are not allowed");
            }

            // Check for link-local
            if (address.isLinkLocalAddress()) {
                throw new IllegalArgumentException("Link-local addresses are not allowed");
            }

        } catch (UnknownHostException e) {
            log.warn("Unable to resolve hostname: {}", host, e);
            throw new IllegalArgumentException("Unable to resolve hostname: " + host, e);
        }

        log.info("URL '{}' passed validation checks", urlString);
    }

    /**
     * Check if host matches a pattern (supports wildcards).
     */
    private boolean matchesPattern(String host, String pattern) {
        if (pattern.equals("*")) {
            return true;
        }
        // Convert wildcard pattern to regex
        String regexPattern = pattern
            .replace(".", "\\.")
            .replace("*", ".*");
        return Pattern.matches(regexPattern, host);
    }

    /**
     * Check if host is in the blacklist.
     */
    private boolean isBlacklisted(String host) {
        return blacklist.stream()
            .filter(entry -> entry != null && !entry.trim().isEmpty())
            .anyMatch(pattern -> matchesPattern(host, pattern.trim()));
    }

    /**
     * Check if host is localhost variant.
     */
    private boolean isLocalhost(String host) {
        return host.equals("localhost") 
            || host.equals("127.0.0.1")
            || host.equals("::1")
            || host.startsWith("127.")
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
            // 10.0.0.0/8
            if (bytes[0] == 10) return true;
            
            // 172.16.0.0/12
            if (bytes[0] == (byte) 172 && (bytes[1] & 0xF0) == 16) return true;
            
            // 192.168.0.0/16
            if (bytes[0] == (byte) 192 && bytes[1] == (byte) 168) return true;
            
            // 169.254.0.0/16 (link-local)
            if (bytes[0] == (byte) 169 && bytes[1] == (byte) 254) return true;
        }

        return false;
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
