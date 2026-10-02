package mcp.server.zap.core.configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import mcp.server.zap.core.controller.AuthController;
import mcp.server.zap.core.exception.GlobalExceptionHandler;
import mcp.server.zap.core.logging.RequestCorrelationHolder;
import mcp.server.zap.core.logging.RequestCorrelationWebFilter;
import mcp.server.zap.core.logging.RequestLogContext;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.JwtService;
import mcp.server.zap.core.service.TokenBlacklistService;
import mcp.server.zap.core.service.protection.AuthEndpointRateLimiter;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import mcp.server.zap.core.service.revocation.InMemoryTokenRevocationStore;
import mcp.server.zap.core.service.revocation.PostgresTokenRevocationStore;
import mcp.server.zap.core.service.revocation.TokenRevocationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtRevocationBackendOutageIntegrationTest {
    private static final String API_KEY = "synthetic-outage-api-key";
    private static final String CLIENT_ID = "outage-client";
    private static final String JWT_SECRET = "synthetic-jwt-secret-at-least-32-characters";
    private static final String DB_USERNAME = "synthetic-database-user";
    private static final String DB_PASSWORD = "synthetic-database-password";
    private static final String CORRELATION_ID = "revocation-outage-264";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @AfterEach
    void clearRequestContext() {
        RequestCorrelationHolder.clearCorrelationId();
        RequestIdentityHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void validJwtCannotAuthenticateOrDowngradeToApiKeyDuringBackendOutage(boolean failFast) {
        Fixture fixture = unavailableFixture(failFast);
        String token = fixture.jwtService.generateAccessToken(CLIENT_ID, List.of("mcp:tools:list"));

        assertUnavailable(fixture.client.get().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(RequestLogContext.CORRELATION_ID_HEADER, CORRELATION_ID)
                .exchange(), token);
        assertUnavailable(fixture.client.get().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-API-Key", API_KEY)
                .header(RequestLogContext.CORRELATION_ID_HEADER, CORRELATION_ID)
                .exchange(), token);

        assertThat(fixture.probe.calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void refreshOutageReturns503WithoutIssuingTokenPair(boolean failFast) {
        Fixture fixture = unavailableFixture(failFast);
        String refreshToken = fixture.jwtService.generateRefreshToken(CLIENT_ID);

        fixture.client.post().uri("/auth/refresh")
                .header(RequestLogContext.CORRELATION_ID_HEADER, CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(RequestLogContext.CORRELATION_ID_HEADER, CORRELATION_ID)
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody().isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revokeOutageCannotAcknowledgeSuccess(boolean failFast) {
        Fixture fixture = unavailableFixture(failFast);
        String token = fixture.jwtService.generateAccessToken(CLIENT_ID, List.of("mcp:tools:list"));

        assertUnavailable(fixture.client.post().uri("/auth/revoke")
                .header(RequestLogContext.CORRELATION_ID_HEADER, CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", token))
                .exchange(), token, fixture.jwtService.getTokenId(token));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void validateControllerOutageCannotReportValidToken(boolean failFast) {
        Fixture fixture = unavailableFixture(failFast);
        String token = fixture.jwtService.generateAccessToken(CLIENT_ID, List.of("mcp:tools:list"));

        // Exercise the controller itself: the protected route would otherwise stop in the JWT filter.
        assertUnavailable(fixture.controllerClient.get().uri("/auth/validate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(RequestLogContext.CORRELATION_ID_HEADER, CORRELATION_ID)
                .exchange(), token, fixture.jwtService.getTokenId(token));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void registeredApiKeyAndTokenIssuanceRemainAvailableDuringBackendOutage(boolean failFast) {
        Fixture fixture = unavailableFixture(failFast);

        fixture.client.get().uri("/mcp")
                .header("X-API-Key", API_KEY)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.clientId").isEqualTo(CLIENT_ID);
        assertThat(fixture.probe.calls.get()).isEqualTo(1);

        TokenPair pair = issueTokens(fixture.client);
        assertThat(fixture.jwtService.getClientIdFromToken(pair.accessToken())).isEqualTo(CLIENT_ID);
        assertThat(fixture.jwtService.getTokenType(pair.refreshToken())).isEqualTo("refresh");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedJwtKeeps401SemanticsEvenWhenBackendIsUnavailable(boolean failFast) {
        Fixture fixture = unavailableFixture(failFast);

        fixture.client.get().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .header("X-API-Key", API_KEY)
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("Invalid or expired JWT token");
        assertThat(fixture.probe.calls.get()).isZero();
        fixture.client.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", "not-a-jwt"))
                .exchange().expectStatus().isUnauthorized();
        fixture.client.post().uri("/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", "not-a-jwt"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void inMemoryAuthenticationRevocationAndRefreshReplayProtectionStillWork() {
        Fixture fixture = new Fixture(new InMemoryTokenRevocationStore());
        TokenPair initial = issueTokens(fixture.client);
        fixture.client.get().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + initial.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.clientId").isEqualTo(CLIENT_ID);

        EntityExchangeResult<Map> rotated = fixture.client.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", initial.refreshToken()))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
        assertThat(rotated.getResponseBody()).isNotNull();
        assertThat(rotated.getResponseBody().get("refreshToken")).isNotEqualTo(initial.refreshToken());
        fixture.client.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", initial.refreshToken()))
                .exchange().expectStatus().isUnauthorized();

        fixture.client.post().uri("/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", initial.accessToken()))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.revoked").isEqualTo(true);
        fixture.client.get().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + initial.accessToken())
                .exchange().expectStatus().isUnauthorized();
        assertThat(fixture.probe.calls.get()).isEqualTo(1);
        fixture.controllerClient.get().uri("/auth/validate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + initial.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.valid").isEqualTo(false);
    }

    private static void assertUnavailable(WebTestClient.ResponseSpec response, String... credentials) {
        EntityExchangeResult<String> result = response
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(RequestLogContext.CORRELATION_ID_HEADER, CORRELATION_ID)
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody(String.class).returnResult();
        String body = result.getResponseBody();
        assertThat(body).isNotBlank()
                .doesNotContain("jdbc:", "127.0.0.1", "postgres", "Postgres", "SQLException",
                        "SELECT", "INSERT", "jwt_token_revocation", DB_USERNAME, DB_PASSWORD, JWT_SECRET, API_KEY)
                .doesNotContain(credentials);
        JsonNode json = OBJECT_MAPPER.readTree(body);
        assertThat(json.path("correlationId").asString()).isEqualTo(CORRELATION_ID);
        assertThat(json.has("error")).isTrue();
        for (String successField : List.of("accessToken", "refreshToken", "valid", "revoked", "tokenId")) {
            assertThat(json.has(successField)).as("Outage response must omit %s", successField).isFalse();
        }
    }

    private static TokenPair issueTokens(WebTestClient client) {
        EntityExchangeResult<Map> result = client.post().uri("/auth/token")
                .header("X-API-Key", API_KEY)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
        Map<?, ?> body = result.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("accessToken")).isInstanceOf(String.class);
        assertThat(body.get("refreshToken")).isInstanceOf(String.class);
        return new TokenPair((String) body.get("accessToken"), (String) body.get("refreshToken"));
    }

    private static Fixture unavailableFixture(boolean failFast) {
        TokenRevocationStoreProperties.Postgres properties = new TokenRevocationStoreProperties.Postgres();
        properties.setUrl("jdbc:postgresql://127.0.0.1:1/revocation_test?connectTimeout=1&socketTimeout=1");
        properties.setUsername(DB_USERNAME);
        properties.setPassword(DB_PASSWORD);
        properties.setFailFast(failFast);
        return new Fixture(new PostgresTokenRevocationStore(properties));
    }

    private record TokenPair(String accessToken, String refreshToken) {}

    private static final class Fixture {
        private final JwtService jwtService;
        private final AuthenticationProbe probe = new AuthenticationProbe();
        private final WebTestClient client;
        private final WebTestClient controllerClient;

        @SuppressWarnings("unchecked")
        private Fixture(TokenRevocationStore store) {
            JwtProperties jwtProperties = new JwtProperties();
            jwtProperties.setSecret(JWT_SECRET);
            jwtProperties.setEnabled(true);
            jwtService = new JwtService(jwtProperties);
            ApiKeyProperties.ApiKeyClient apiKeyClient = new ApiKeyProperties.ApiKeyClient();
            apiKeyClient.setKey(API_KEY);
            apiKeyClient.setClientId(CLIENT_ID);
            apiKeyClient.setScopes(List.of("mcp:tools:list"));
            ApiKeyProperties apiKeyProperties = new ApiKeyProperties();
            apiKeyProperties.setApiKeys(List.of(apiKeyClient));
            TokenBlacklistService blacklist = new TokenBlacklistService(store);
            AuthController controller = new AuthController(jwtService, apiKeyProperties, blacklist);
            ObjectProvider<JwtService> jwtProvider = mock(ObjectProvider.class);
            when(jwtProvider.getIfAvailable()).thenReturn(jwtService);
            ObjectProvider<TokenBlacklistService> blacklistProvider = mock(ObjectProvider.class);
            when(blacklistProvider.getIfAvailable()).thenReturn(blacklist);
            ObjectProvider<ObjectMapper> mapperProvider = mock(ObjectProvider.class);
            when(mapperProvider.getIfAvailable(any())).thenReturn(OBJECT_MAPPER);
            ObservabilityService observability = mock(ObservabilityService.class);
            SecurityConfig security = new SecurityConfig(jwtProperties, apiKeyProperties, jwtProvider,
                    blacklistProvider, mapperProvider, observability, mock(AuthEndpointRateLimiter.class));
            ReflectionTestUtils.setField(security, "securityModeConfig", "jwt");
            ReflectionTestUtils.setField(security, "securityEnabled", true);
            client = WebTestClient.bindToController(controller, probe)
                    .controllerAdvice(new GlobalExceptionHandler())
                    .webFilter(new RequestCorrelationWebFilter(observability), security.authenticationFilter())
                    .build();
            controllerClient = WebTestClient.bindToController(controller)
                    .controllerAdvice(new GlobalExceptionHandler())
                    .webFilter(new RequestCorrelationWebFilter(observability))
                    .build();
        }
    }

    @RestController
    static class AuthenticationProbe {
        private final AtomicInteger calls = new AtomicInteger();

        @GetMapping("/mcp")
        Mono<Map<String, String>> authenticatedClient() {
            calls.incrementAndGet();
            return ReactiveSecurityContextHolder.getContext()
                    .map(context -> Map.of("clientId", context.getAuthentication().getName()))
                    .defaultIfEmpty(Map.of("clientId", "anonymous"));
        }
    }
}
