package mcp.server.zap.core.service.auth.bootstrap;

import java.util.Locale;

/**
 * Guided auth bootstrap kinds supported in the current gateway window.
 */
public enum AuthBootstrapKind {
    FORM("form"),
    BEARER("bearer"),
    API_KEY("api-key");

    private final String wireValue;

    AuthBootstrapKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static AuthBootstrapKind fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("authKind cannot be null or blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "form" -> FORM;
            case "bearer" -> BEARER;
            case "api-key", "api_key" -> API_KEY;
            default -> throw new IllegalArgumentException("authKind must be one of: form, bearer, api-key");
        };
    }
}
