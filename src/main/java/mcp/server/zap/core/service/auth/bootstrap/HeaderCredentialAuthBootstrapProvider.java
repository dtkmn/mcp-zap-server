package mcp.server.zap.core.service.auth.bootstrap;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import mcp.server.zap.core.gateway.TargetDescriptor;
import org.springframework.stereotype.Component;

/**
 * Guided provider for bearer or API-key credential references at the gateway layer.
 */
@Component
public class HeaderCredentialAuthBootstrapProvider implements AuthBootstrapProvider {
    private final CredentialReferenceResolver credentialReferenceResolver;

    public HeaderCredentialAuthBootstrapProvider(CredentialReferenceResolver credentialReferenceResolver) {
        this.credentialReferenceResolver = credentialReferenceResolver;
    }

    @Override
    public String providerId() {
        return "gateway-header-reference";
    }

    @Override
    public boolean supports(AuthBootstrapRequest request) {
        return request.authKind() == AuthBootstrapKind.BEARER || request.authKind() == AuthBootstrapKind.API_KEY;
    }

    @Override
    public AuthSessionPrepareResult prepare(AuthBootstrapRequest request) {
        String targetUrl = requireText(request.targetUrl(), "targetUrl");
        credentialReferenceResolver.resolveSecret(request.credentialReference(), request.inlineSecret());

        PreparedAuthSession session = new PreparedAuthSession(
                UUID.randomUUID().toString(),
                defaultSessionLabel(request.sessionLabel(), targetUrl, request.authKind()),
                request.authKind(),
                providerId(),
                new TargetDescriptor(TargetDescriptor.Kind.API, targetUrl, targetUrl),
                trimToNull(request.credentialReference()),
                null,
                null,
                null,
                null,
                hasText(request.headerName()) ? request.headerName().trim() : defaultHeaderName(request.authKind()),
                null,
                false
        );

        List<String> warnings = new ArrayList<>();
        if (!hasText(request.credentialReference()) && hasText(request.inlineSecret())) {
            warnings.add("Inline secret was accepted for this local workflow. Prefer credentialReference for repeatable operator paths.");
        }
        warnings.add("Current guided ZAP flows do not automatically inject header-based auth yet. This session is stored as a gateway contract and validation scaffold.");

        return new AuthSessionPrepareResult(session, warnings);
    }

    @Override
    public AuthSessionValidationResult validate(PreparedAuthSession session) {
        if (!hasText(session.credentialReference())) {
            return new AuthSessionValidationResult(
                    session,
                    false,
                    "reference_missing",
                    List.of(
                            "This session was prepared from an inline secret and cannot be revalidated without re-preparing it.",
                            "Prefer credentialReference values like env:NAME or file:/absolute/path for repeatable operator workflows."
                    )
            );
        }
        credentialReferenceResolver.resolveSecret(session.credentialReference(), null);
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("credentialReference resolved successfully");
        diagnostics.add("headerName=" + session.headerName());
        diagnostics.add("Current guided ZAP flows do not automatically inject header-based auth yet.");
        return new AuthSessionValidationResult(
                session,
                true,
                "reference_valid",
                diagnostics
        );
    }

    private String defaultHeaderName(AuthBootstrapKind authKind) {
        return authKind == AuthBootstrapKind.BEARER ? "Authorization" : "X-API-Key";
    }

    private String defaultSessionLabel(String sessionLabel, String targetUrl, AuthBootstrapKind authKind) {
        if (hasText(sessionLabel)) {
            return sessionLabel.trim();
        }
        URI uri = URI.create(targetUrl);
        String host = uri.getHost() == null ? "target" : uri.getHost().toLowerCase(Locale.ROOT);
        return host + "-" + authKind.wireValue();
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
