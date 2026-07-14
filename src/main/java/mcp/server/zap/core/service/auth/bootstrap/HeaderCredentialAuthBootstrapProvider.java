package mcp.server.zap.core.service.auth.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mcp.server.zap.core.gateway.TargetDescriptor;
import mcp.server.zap.core.service.UrlValidationService;
import org.springframework.stereotype.Component;

/**
 * Guided provider for bearer or API-key credential references at the gateway layer.
 */
@Component
public class HeaderCredentialAuthBootstrapProvider implements AuthBootstrapProvider {
    private final CredentialReferenceResolver credentialReferenceResolver;
    private final UrlValidationService urlValidationService;

    public HeaderCredentialAuthBootstrapProvider(CredentialReferenceResolver credentialReferenceResolver,
                                                 UrlValidationService urlValidationService) {
        this.credentialReferenceResolver = credentialReferenceResolver;
        this.urlValidationService = urlValidationService;
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
        String profileId = requireText(request.profileId(), "profileId");
        String targetUrl = requireText(request.targetUrl(), "targetUrl");
        urlValidationService.validateUrl(targetUrl);
        if (request.allowedOrigin() == null) {
            throw new IllegalArgumentException("Auth profile allowedOrigin cannot be null");
        }
        request.allowedOrigin().requireMatch(targetUrl, "targetUrl");
        credentialReferenceResolver.resolveSecret(request.credentialReference());

        PreparedAuthSession session = new PreparedAuthSession(
                UUID.randomUUID().toString(),
                profileId,
                request.authKind(),
                providerId(),
                new TargetDescriptor(TargetDescriptor.Kind.API, targetUrl, targetUrl),
                request.allowedOrigin(),
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
        warnings.add("Current guided ZAP flows do not automatically inject header-based auth yet. This session is stored as a gateway contract and validation scaffold.");

        return new AuthSessionPrepareResult(session, warnings);
    }

    @Override
    public AuthSessionValidationResult validate(PreparedAuthSession session) {
        if (session.authorizedOrigin() == null) {
            throw new IllegalArgumentException("Prepared auth session has no authorized profile origin");
        }
        session.authorizedOrigin().requireMatch(session.target().baseUrl(), "auth session target");
        credentialReferenceResolver.resolveSecret(session.credentialReference());
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
