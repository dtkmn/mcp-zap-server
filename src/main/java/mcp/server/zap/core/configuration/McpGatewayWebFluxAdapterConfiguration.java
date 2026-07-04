package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.spring.webflux.McpAuthorizationObserver;
import mcp.gateway.spring.webflux.McpGatewayAbuseProtectionEvaluator;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationEvaluator;
import mcp.gateway.spring.webflux.McpGatewayAuthorizationMode;
import mcp.gateway.spring.webflux.McpGatewayCorrelationIdResolver;
import mcp.gateway.spring.webflux.McpGatewayWebFluxContextResolver;
import mcp.gateway.spring.webflux.McpGatewayWebFluxGovernanceFilter;
import mcp.gateway.spring.webflux.McpGatewayWebFluxProperties;
import mcp.gateway.spring.webflux.McpGrantedScopesExtractor;
import mcp.gateway.spring.webflux.McpInvalidRequestObserver;
import mcp.gateway.spring.webflux.McpProtectionRejectionObserver;
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

/**
 * Wires the public Spring WebFlux adapter to the OSS ZAP/security runtime.
 */
@Configuration
public class McpGatewayWebFluxAdapterConfiguration {

    @Bean
    McpGatewayWebFluxProperties mcpGatewayWebFluxProperties(
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint,
            @Value("${mcp.server.request.max-body-bytes:262144}") int maxBodyBytes) {
        return new McpGatewayWebFluxProperties(
                mcpEndpoint,
                maxBodyBytes,
                SecurityWebFiltersOrder.AUTHORIZATION.getOrder() + 1
        );
    }

    @Bean
    McpGatewayCorrelationIdResolver mcpGatewayCorrelationIdResolver() {
        return RequestLogContext::correlationId;
    }

    @Bean
    McpGatewayWebFluxContextResolver mcpGatewayWebFluxContextResolver(
            ClientWorkspaceResolver clientWorkspaceResolver,
            McpGatewayCorrelationIdResolver correlationIdResolver) {
        return (authentication, exchange, invocation) -> clientWorkspaceResolver.resolveToolExecutionContext(
                authentication,
                correlationIdResolver.resolve(exchange),
                invocation,
                null
        );
    }

    @Bean
    McpGatewayAuthorizationEvaluator mcpGatewayAuthorizationEvaluator(
            ToolAuthorizationService toolAuthorizationService,
            @Value("${mcp.server.security.enabled:true}") boolean securityEnabled,
            @Value("${mcp.server.security.mode:api-key}") String securityMode) {
        return new McpGatewayAuthorizationEvaluator() {
            @Override
            public McpGatewayAuthorizationMode mode() {
                if (!securityEnabled || securityModeIsNone(securityMode) || toolAuthorizationService.isDisabled()) {
                    return McpGatewayAuthorizationMode.DISABLED;
                }
                return toolAuthorizationService.isWarnOnly()
                        ? McpGatewayAuthorizationMode.WARN
                        : McpGatewayAuthorizationMode.ENFORCE;
            }

            @Override
            public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                                       GatewayToolExecutionContext context) {
                return toolAuthorizationService.authorize(grantedScopes, context);
            }
        };
    }

    private boolean securityModeIsNone(String securityMode) {
        return securityMode != null && "none".equalsIgnoreCase(securityMode.trim());
    }

    @Bean
    McpAuthorizationObserver mcpAuthorizationObserver(ObservabilityService observabilityService) {
        return observation -> observabilityService.recordAuthorization(
                observation.actionName(),
                observation.outcome(),
                observation.reason(),
                observation.requiredScopes(),
                observation.grantedScopes(),
                observation.context() == null ? null : observation.context().principalId(),
                observation.context() == null ? null : observation.context().workspaceId(),
                observation.context() == null ? null : observation.context().correlationId()
        );
    }

    @Bean
    McpGatewayAbuseProtectionEvaluator mcpGatewayAbuseProtectionEvaluator(
            McpAbuseProtectionService protectionService) {
        return new McpGatewayAbuseProtectionEvaluator() {
            @Override
            public boolean enabled() {
                return protectionService.isEnabled();
            }

            @Override
            public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext context) {
                return protectionService.evaluate(context);
            }
        };
    }

    @Bean
    McpProtectionRejectionObserver mcpProtectionRejectionObserver(ObservabilityService observabilityService) {
        return (decision, context) -> observabilityService.recordProtectionRejection(
                decision,
                context == null ? null : context.correlationId()
        );
    }

    @Bean
    McpInvalidRequestObserver mcpInvalidRequestObserver(ObservabilityService observabilityService) {
        return observabilityService::recordInvalidMcpRequest;
    }

    @Bean
    McpGatewayWebFluxGovernanceFilter mcpGatewayWebFluxGovernanceFilter(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            McpGatewayWebFluxProperties properties,
            McpGatewayAuthorizationEvaluator authorizationEvaluator,
            McpGatewayAbuseProtectionEvaluator protectionEvaluator,
            McpGatewayWebFluxContextResolver contextResolver,
            McpAuthorizationObserver authorizationObserver,
            McpProtectionRejectionObserver rejectionObserver,
            McpGatewayCorrelationIdResolver correlationIdResolver,
            McpInvalidRequestObserver invalidRequestObserver) {
        return new McpGatewayWebFluxGovernanceFilter(
                objectMapperProvider.getIfAvailable(ObjectMapper::new),
                properties,
                authorizationEvaluator,
                protectionEvaluator,
                contextResolver,
                McpGrantedScopesExtractor.springSecurityScopes(),
                authorizationObserver,
                rejectionObserver,
                correlationIdResolver,
                invalidRequestObserver
        );
    }
}
