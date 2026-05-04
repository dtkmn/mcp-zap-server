package mcp.server.zap.core.service.auth.bootstrap;

/**
 * SPI for gateway auth bootstrap preparation and validation.
 */
public interface AuthBootstrapProvider {

    String providerId();

    boolean supports(AuthBootstrapRequest request);

    AuthSessionPrepareResult prepare(AuthBootstrapRequest request);

    AuthSessionValidationResult validate(PreparedAuthSession session);
}
