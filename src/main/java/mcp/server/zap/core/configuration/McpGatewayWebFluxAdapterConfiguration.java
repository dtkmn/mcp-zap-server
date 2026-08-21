package mcp.server.zap.core.configuration;

import mcp.gateway.spring.webflux.McpGatewayAuthorizationMode;
import mcp.gateway.spring.webflux.McpGatewayCorrelationIdResolver;
import mcp.gateway.spring.webflux.McpGatewayWebFluxGovernanceFilter;
import mcp.gateway.spring.webflux.McpGatewayWebFluxProperties;
import mcp.server.zap.core.logging.RequestLogContext;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.authz.ToolAuthorizationService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.McpAbuseProtectionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the public Spring WebFlux adapter to the OSS ZAP/security runtime.
 */
@Configuration
public class McpGatewayWebFluxAdapterConfiguration {

    @Bean
    McpGatewayWebFluxGovernanceFilter mcpGatewayWebFluxGovernanceFilter(
            ObjectProvider<JsonMapper> jsonMapperProvider,
            ClientWorkspaceResolver clientWorkspaceResolver,
            ToolAuthorizationService toolAuthorizationService,
            McpAbuseProtectionService protectionService,
            ObservabilityService observabilityService,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint,
            @Value("${mcp.server.request.max-body-bytes:262144}") int maxBodyBytes,
            @Value("${mcp.server.security.enabled:true}") boolean securityEnabled,
            @Value("${mcp.server.security.mode:api-key}") String securityMode) {
        McpGatewayCorrelationIdResolver correlationIdResolver = RequestLogContext::correlationId;
        return McpGatewayWebFluxGovernanceFilter.builder(
                        jsonMapperProvider.getIfAvailable(() -> JsonMapper.builder().build()),
                        (authentication, exchange, invocation) ->
                                clientWorkspaceResolver.resolveToolExecutionContext(
                                        authentication,
                                        correlationIdResolver.resolve(exchange),
                                        invocation,
                                        null
                                )
                )
                .properties(new McpGatewayWebFluxProperties(
                        mcpEndpoint,
                        maxBodyBytes,
                        SecurityWebFiltersOrder.AUTHORIZATION.getOrder() + 1
                ))
                .authorization(
                        () -> authorizationMode(securityEnabled, securityMode, toolAuthorizationService),
                        toolAuthorizationService::authorize
                )
                .protection(protectionService::isEnabled, protectionService::evaluate)
                .authorizationObserver(observation -> observabilityService.recordAuthorization(
                        observation.actionName(),
                        observation.outcome(),
                        observation.reason(),
                        observation.requiredScopes(),
                        observation.grantedScopes(),
                        observation.context() == null ? null : observation.context().principalId(),
                        observation.context() == null ? null : observation.context().workspaceId(),
                        observation.context() == null ? null : observation.context().correlationId()
                ))
                .protectionRejectionObserver((decision, context) ->
                        observabilityService.recordProtectionRejection(
                                decision,
                                context == null ? null : context.correlationId()
                        ))
                .correlationIdResolver(correlationIdResolver)
                .invalidRequestObserver(observabilityService::recordInvalidMcpRequest)
                .build();
    }

    private static McpGatewayAuthorizationMode authorizationMode(
            boolean securityEnabled,
            String securityMode,
            ToolAuthorizationService toolAuthorizationService) {
        if (!securityEnabled || securityModeIsNone(securityMode) || toolAuthorizationService.isDisabled()) {
            return McpGatewayAuthorizationMode.DISABLED;
        }
        return toolAuthorizationService.isWarnOnly()
                ? McpGatewayAuthorizationMode.WARN
                : McpGatewayAuthorizationMode.ENFORCE;
    }

    private static boolean securityModeIsNone(String securityMode) {
        return securityMode != null && "none".equalsIgnoreCase(securityMode.trim());
    }
}
