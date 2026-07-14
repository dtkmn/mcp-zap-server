package mcp.server.zap.core.service.auth.bootstrap;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/**
 * Canonical HTTP origin used to bind an operator-managed credential to one relying party.
 */
public record HttpOrigin(String scheme, String host, int port) {

    public HttpOrigin {
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("scheme must be http or https");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host cannot be null or blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    public static HttpOrigin fromConfiguredOrigin(String value) {
        URI uri = parse(value, "allowedOrigin");
        String path = uri.getRawPath();
        if ((path != null && !path.isBlank() && !"/".equals(path))
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("allowedOrigin must contain only scheme, host, and optional port");
        }
        return fromUri(uri, "allowedOrigin");
    }

    public static HttpOrigin fromUrl(String value) {
        return fromUri(parse(value, "URL"), "URL");
    }

    public boolean matches(String url) {
        return equals(fromUrl(url));
    }

    public void requireMatch(String url, String fieldName) {
        if (!matches(url)) {
            throw new IllegalArgumentException(fieldName + " origin is not authorized for auth profile");
        }
    }

    @Override
    public String toString() {
        String formattedHost = host.contains(":") ? "[" + host + "]" : host;
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        return scheme + "://" + formattedHost + (port == defaultPort ? "" : ":" + port);
    }

    private static URI parse(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " must be a valid absolute HTTP(S) URL", e);
        }
    }

    private static HttpOrigin fromUri(URI uri, String fieldName) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(fieldName + " must use http or https");
        }
        if (uri.isOpaque() || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(fieldName + " must not contain user information");
        }
        if (uri.getPort() < 0
                && uri.getRawAuthority() != null
                && uri.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException(fieldName + " port cannot be empty");
        }

        String host = normalizeHost(uri.getHost(), fieldName);
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return new HttpOrigin(scheme, host, port);
    }

    private static String normalizeHost(String rawHost, String fieldName) {
        if (rawHost == null || rawHost.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must include a host");
        }
        String host = rawHost.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.endsWith(".")) {
            throw new IllegalArgumentException(fieldName + " host must not end with a dot");
        }
        if (host.contains(":")) {
            return host.toLowerCase(Locale.ROOT);
        }
        try {
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " host is invalid", e);
        }
    }
}
