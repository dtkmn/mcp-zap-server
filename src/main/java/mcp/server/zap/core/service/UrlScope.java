package mcp.server.zap.core.service;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class UrlScope {
    private final String scheme;
    private final String host;
    private final int port;
    private final String path;

    private UrlScope(String scheme, String host, int port, String path) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.path = path;
    }

    static UrlScope parse(String baseUrl) {
        UrlParts parts = parseParts(baseUrl);
        if (parts == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute URL with scheme and host");
        }
        return new UrlScope(parts.scheme(), parts.host(), parts.port(), parts.path());
    }

    boolean contains(String candidateUrl) {
        UrlParts candidate = parseParts(candidateUrl);
        return candidate != null
                && scheme.equals(candidate.scheme())
                && host.equals(candidate.host())
                && port == candidate.port()
                && containsPath(candidate.path());
    }

    private boolean containsPath(String candidatePath) {
        if ("/".equals(path)) {
            return true;
        }
        return candidatePath.equals(path) || candidatePath.startsWith(path + "/");
    }

    private static UrlParts parseParts(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(value.trim()).normalize();
            String scheme = normalizeScheme(uri.getScheme());
            String host = normalizeHost(uri.getHost());
            if (scheme == null || host == null) {
                return null;
            }
            return new UrlParts(scheme, host, effectivePort(scheme, uri.getPort()), normalizePath(uri.getPath()));
        } catch (IllegalArgumentException | URISyntaxException e) {
            return null;
        }
    }

    private static String normalizeScheme(String scheme) {
        if (scheme == null || scheme.isBlank()) {
            return null;
        }
        return scheme.toLowerCase(Locale.ROOT);
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        return IDN.toASCII(host.trim().toLowerCase(Locale.ROOT));
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    private static String normalizePath(String path) throws URISyntaxException {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = new URI(null, null, path, null).normalize().getPath();
        if (normalized == null || normalized.isBlank()) {
            return "/";
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private record UrlParts(String scheme, String host, int port, String path) {
    }
}
