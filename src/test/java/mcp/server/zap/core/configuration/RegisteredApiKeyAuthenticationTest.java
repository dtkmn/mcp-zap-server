package mcp.server.zap.core.configuration;

import mcp.server.zap.core.logging.RequestCorrelationHolder;
import mcp.server.zap.core.logging.RequestLogContext;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.JwtService;
import mcp.server.zap.core.service.TokenBlacklistService;
import mcp.server.zap.core.service.protection.AuthEndpointRateLimiter;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RegisteredApiKeyAuthenticationTest {
    @AfterEach
    void clearThreadContext() {
        SecurityContextHolder.clearContext();
        RequestIdentityHolder.clear();
        RequestCorrelationHolder.clearCorrelationId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-key", "jwt"})
    void registeredClientRetainsItsIdentityWorkspaceAndScopes(String mode) {
        ApiKeyProperties.ApiKeyClient client = client(List.of("mcp:tools:list", "zap:report:read"));
        MockServerWebExchange exchange = exchange("registered-key");
        Authentication authentication = authenticate(mode, client, exchange);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("registered-client");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER", "SCOPE_mcp:tools:list", "SCOPE_zap:report:read");
        assertThat(exchange.<String>getAttribute(RequestLogContext.WORKSPACE_ID_ATTRIBUTE)).isEqualTo("shared-workspace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-key", "jwt"})
    void nullScopesNeverBecomeWildcardPermissions(String mode) {
        Authentication authentication = authenticate(mode, client(null), exchange("registered-key"));

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-key", "jwt"})
    void explicitlyRegisteredWildcardPermissionsRemainSupported(String mode) {
        Authentication authentication = authenticate(mode, client(List.of("*")), exchange("registered-key"));

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER", "SCOPE_*");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-key", "jwt"})
    void unregisteredKeyDoesNotReachAuthenticatedChain(String mode) {
        MockServerWebExchange exchange = exchange("unregistered-key");
        Authentication authentication = authenticate(mode, client(List.of("*")), exchange);

        assertThat(authentication).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate")).isEqualTo("API-Key");
    }

    private Authentication authenticate(String mode, ApiKeyProperties.ApiKeyClient client, MockServerWebExchange exchange) {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeys(List.of(client));
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        SecurityConfig security = new SecurityConfig(new JwtProperties(), properties,
                factory.getBeanProvider(JwtService.class), factory.getBeanProvider(TokenBlacklistService.class),
                factory.getBeanProvider(ObjectMapper.class), mock(ObservabilityService.class), mock(AuthEndpointRateLimiter.class));
        ReflectionTestUtils.setField(security, "securityEnabled", true);
        ReflectionTestUtils.setField(security, "securityModeConfig", mode);
        AtomicReference<Authentication> authentication = new AtomicReference<>();
        security.authenticationFilter().filter(exchange, ignored -> ReactiveSecurityContextHolder.getContext()
                        .doOnNext(context -> authentication.set(context.getAuthentication()))
                        .then(Mono.empty()))
                .block();
        return authentication.get();
    }

    private ApiKeyProperties.ApiKeyClient client(List<String> scopes) {
        ApiKeyProperties.ApiKeyClient client = new ApiKeyProperties.ApiKeyClient();
        client.setKey("registered-key");
        client.setClientId("registered-client");
        client.setWorkspaceId("shared-workspace");
        client.setScopes(scopes);
        return client;
    }

    private MockServerWebExchange exchange(String key) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/mcp").header("X-API-Key", key));
    }
}
