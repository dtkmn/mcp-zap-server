package mcp.server.zap.core.service.auth.bootstrap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Guided auth bootstrap orchestrator for prepare and validate flows.
 */
@Service
public class GuidedAuthSessionService {
    private final List<AuthBootstrapProvider> providers;
    private final PreparedAuthSessionRegistry preparedAuthSessionRegistry;

    public GuidedAuthSessionService(List<AuthBootstrapProvider> providers,
                                    PreparedAuthSessionRegistry preparedAuthSessionRegistry) {
        this.providers = providers;
        this.preparedAuthSessionRegistry = preparedAuthSessionRegistry;
    }

    public String prepareSession(String targetUrl,
                                 String authKind,
                                 String credentialReference,
                                 String inlineSecret,
                                 String sessionLabel,
                                 String contextName,
                                 String loginUrl,
                                 String username,
                                 String userName,
                                 String usernameField,
                                 String passwordField,
                                 String headerName,
                                 String loggedInIndicatorRegex,
                                 String loggedOutIndicatorRegex) {
        AuthBootstrapRequest request = new AuthBootstrapRequest(
                sessionLabel,
                targetUrl,
                AuthBootstrapKind.fromWireValue(authKind),
                trimToNull(credentialReference),
                trimToNull(inlineSecret),
                trimToNull(contextName),
                trimToNull(loginUrl),
                trimToNull(username),
                trimToNull(userName),
                trimToNull(usernameField),
                trimToNull(passwordField),
                trimToNull(headerName),
                trimToNull(loggedInIndicatorRegex),
                trimToNull(loggedOutIndicatorRegex)
        );

        AuthBootstrapProvider provider = resolveProvider(request);
        AuthSessionPrepareResult result = provider.prepare(request);
        PreparedAuthSession session = preparedAuthSessionRegistry.save(result.session());

        StringBuilder output = new StringBuilder()
                .append("Guided auth session prepared.\n")
                .append("Session ID: ").append(session.sessionId()).append('\n')
                .append("Session Label: ").append(session.sessionLabel()).append('\n')
                .append("Auth Kind: ").append(session.authKind().wireValue()).append('\n')
                .append("Provider: ").append(session.providerId()).append('\n')
                .append("Target URL: ").append(session.target().baseUrl()).append('\n')
                .append("Engine Binding: ").append(session.engineBound() ? "ZAP context/user ready" : "gateway contract only").append('\n');
        if (hasText(session.contextName())) {
            output.append("Context Name: ").append(session.contextName()).append('\n');
        }
        if (hasText(session.contextId())) {
            output.append("Context ID: ").append(session.contextId()).append('\n');
        }
        if (hasText(session.userName())) {
            output.append("User Name: ").append(session.userName()).append('\n');
        }
        if (hasText(session.userId())) {
            output.append("User ID: ").append(session.userId()).append('\n');
        }
        if (hasText(session.headerName())) {
            output.append("Header Name: ").append(session.headerName()).append('\n');
        }
        if (hasText(session.credentialReference())) {
            output.append("Credential Source: ").append(session.credentialReference()).append('\n');
        } else {
            output.append("Credential Source: inline-secret\n");
        }
        output.append("Next Step: call zap_auth_session_validate with this session ID before running authenticated crawl or attack flows.");

        appendWarnings(output, result.warnings());
        return output.toString();
    }

    public String validateSession(String sessionId) {
        PreparedAuthSession session = getPreparedSession(sessionId);
        AuthBootstrapProvider provider = providers.stream()
                .filter(candidate -> candidate.providerId().equals(session.providerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No auth bootstrap provider registered for " + session.providerId()));
        AuthSessionValidationResult result = provider.validate(session);

        StringBuilder output = new StringBuilder()
                .append("Guided auth session validation complete.\n")
                .append("Session ID: ").append(session.sessionId()).append('\n')
                .append("Auth Kind: ").append(session.authKind().wireValue()).append('\n')
                .append("Provider: ").append(session.providerId()).append('\n')
                .append("Valid: ").append(result.valid()).append('\n')
                .append("Outcome: ").append(result.outcome()).append('\n');
        if (hasText(session.contextId())) {
            output.append("Context ID: ").append(session.contextId()).append('\n');
        }
        if (hasText(session.userId())) {
            output.append("User ID: ").append(session.userId()).append('\n');
        }
        if (!result.diagnostics().isEmpty()) {
            output.append("Diagnostics:\n");
            for (String diagnostic : result.diagnostics()) {
                output.append("- ").append(diagnostic).append('\n');
            }
        }
        return output.toString().trim();
    }

    public PreparedAuthSession getPreparedSession(String sessionId) {
        String normalizedSessionId = requireText(sessionId, "sessionId");
        return preparedAuthSessionRegistry.findById(normalizedSessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown auth session ID: " + normalizedSessionId));
    }

    Map<String, List<String>> capabilityMatrix() {
        Map<String, List<String>> matrix = new LinkedHashMap<>();
        for (AuthBootstrapProvider provider : providers) {
            matrix.put(provider.providerId(), List.of());
        }
        return matrix;
    }

    private AuthBootstrapProvider resolveProvider(AuthBootstrapRequest request) {
        return providers.stream()
                .filter(provider -> provider.supports(request))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No auth bootstrap provider is available for authKind=" + request.authKind().wireValue()
                ));
    }

    private void appendWarnings(StringBuilder output, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        output.append('\n').append("Warnings:\n");
        for (String warning : warnings) {
            output.append("- ").append(warning).append('\n');
        }
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
