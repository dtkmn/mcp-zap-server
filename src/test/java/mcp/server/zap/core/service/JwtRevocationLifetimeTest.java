package mcp.server.zap.core.service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import mcp.server.zap.core.configuration.ApiKeyProperties;
import mcp.server.zap.core.configuration.JwtProperties;
import mcp.server.zap.core.configuration.SecurityConfig;
import mcp.server.zap.core.controller.AuthController;
import mcp.server.zap.core.logging.RequestCorrelationHolder;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.protection.AuthEndpointRateLimiter;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import mcp.server.zap.core.service.revocation.InMemoryTokenRevocationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtRevocationLifetimeTest {
    private static final String SECRET = "synthetic-lifetime-secret-at-least-32-characters";
    private static final String CLIENT_ID = "lifetime-client";
    private static final Instant EXPIRY = Instant.parse("2030-01-01T00:05:00Z");
    private static final Instant ISSUED_AT = EXPIRY.minusSeconds(300);

    @AfterEach
    void clearRequestContext() {
        RequestCorrelationHolder.clearCorrelationId();
        RequestIdentityHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"access", "refresh"})
    void decoderAcceptsThroughInclusiveSixtySecondGraceAndRejectsAfterward(String type) {
        Fixture fixture = new Fixture();
        String token = fixture.token(type);

        for (Instant now : List.of(EXPIRY.minusNanos(1), EXPIRY, EXPIRY.plusSeconds(1),
                EXPIRY.plusSeconds(59), EXPIRY.plusSeconds(60))) {
            fixture.clock.set(now);
            assertThat(fixture.jwtService.validateToken(token).getExpiresAt())
                    .as("%s token must remain decodable at %s", type, now).isEqualTo(EXPIRY);
        }

        fixture.clock.set(EXPIRY.plusSeconds(60).plusNanos(1));
        assertThatThrownBy(() -> fixture.jwtService.validateToken(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void decoderPreservesNotBeforeGrace() {
        Fixture fixture = new Fixture();
        Instant notBefore = EXPIRY.plusSeconds(120);
        String token = sign(JwtClaimsSet.builder()
                .subject(CLIENT_ID).issuer("lifetime-test").issuedAt(ISSUED_AT)
                .notBefore(notBefore).expiresAt(notBefore.plusSeconds(300))
                .id("not-before-token").claim("type", "access").build());

        fixture.clock.set(notBefore.minusSeconds(60));
        assertThat(fixture.jwtService.validateToken(token).getNotBefore()).isEqualTo(notBefore);
        fixture.clock.set(notBefore.minusSeconds(60).minusNanos(1));
        assertThatThrownBy(() -> fixture.jwtService.validateToken(token)).isInstanceOf(JwtException.class);
        fixture.clock.set(notBefore);
        assertThat(fixture.jwtService.validateToken(token).getNotBefore()).isEqualTo(notBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"access", "refresh"})
    void signedTokensWithoutExpiryAreRejectedByDecoderAndController(String type) {
        Fixture fixture = new Fixture();
        String token = sign(JwtClaimsSet.builder()
                .subject(CLIENT_ID).issuer("lifetime-test").issuedAt(ISSUED_AT)
                .id("missing-exp-" + type).claim("type", type).build());

        assertThatThrownBy(() -> fixture.jwtService.validateToken(token)).isInstanceOf(JwtException.class);
        fixture.controllerClient.get().uri("/auth/validate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.valid").isEqualTo(false);
        fixture.controllerClient.post().uri("/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("token", token))
                .exchange().expectStatus().isUnauthorized();
        fixture.controllerClient.post().uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("refreshToken", token))
                .exchange().expectStatus().isUnauthorized();
        fixture.securedClient.get().uri("/auth/validate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isUnauthorized();
        assertThat(fixture.store.size()).isZero();
    }

    @Test
    void revokedAccessAndConsumedRefreshRemainDeniedThroughoutDecoderGrace() {
        Fixture fixture = new Fixture();
        String revokedAccess = fixture.token("access");
        String unrevokedAccess = fixture.token("access");
        String consumedRefresh = fixture.token("refresh");
        fixture.revoke(revokedAccess);
        fixture.refresh(consumedRefresh).expectStatus().isOk();

        for (Instant now : List.of(EXPIRY, EXPIRY.plusSeconds(1), EXPIRY.plusSeconds(59), EXPIRY.plusSeconds(60))) {
            fixture.clock.set(now);
            assertThat(fixture.jwtService.validateToken(revokedAccess).getExpiresAt()).isEqualTo(EXPIRY);
            assertThat(fixture.jwtService.validateToken(consumedRefresh).getExpiresAt()).isEqualTo(EXPIRY);
            fixture.store.cleanupExpired();
            assertThat(fixture.store.size()).as("Revocations remain active at %s", now).isEqualTo(2);
            fixture.controllerClient.get().uri("/auth/validate")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokedAccess)
                    .exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.valid").isEqualTo(false)
                    .jsonPath("$.error").isEqualTo("Token has been revoked");
            fixture.controllerClient.get().uri("/auth/validate")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + unrevokedAccess)
                    .exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.valid").isEqualTo(true);
            fixture.securedClient.get().uri("/auth/validate")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokedAccess)
                    .exchange().expectStatus().isUnauthorized();
            fixture.securedClient.get().uri("/auth/validate")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + unrevokedAccess)
                    .exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.valid").isEqualTo(true);
            fixture.refresh(consumedRefresh).expectStatus().isUnauthorized();
        }

        fixture.clock.set(EXPIRY.plusSeconds(60).plusNanos(1));
        assertThat(fixture.store.size()).isZero();
        fixture.refresh(consumedRefresh).expectStatus().isUnauthorized();
        fixture.controllerClient.get().uri("/auth/validate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + unrevokedAccess)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.valid").isEqualTo(false);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 59, 60})
    void firstRefreshAndNewAccessRevocationSucceedInGraceWithoutExtendingItAgain(long secondsAfterExpiry) {
        Fixture fixture = new Fixture();
        String refresh = fixture.token("refresh");
        String access = fixture.token("access");
        fixture.clock.set(EXPIRY.plusSeconds(secondsAfterExpiry));

        Map<?, ?> response = fixture.refresh(refresh).expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        String rotatedAccess = (String) response.get("accessToken");
        String rotatedRefresh = (String) response.get("refreshToken");
        assertThat(rotatedAccess).isNotBlank().isNotEqualTo(access);
        assertThat(rotatedRefresh).isNotBlank().isNotEqualTo(refresh);
        assertThat(fixture.jwtService.validateToken(rotatedAccess).getSubject()).isEqualTo(CLIENT_ID);
        assertThat(fixture.jwtService.validateToken(rotatedRefresh).getExpiresAt())
                .isEqualTo(fixture.clock.instant().plusSeconds(300));
        fixture.refresh(refresh).expectStatus().isUnauthorized();
        fixture.revoke(access);
        assertThat(fixture.store.size()).isEqualTo(2);

        fixture.clock.set(EXPIRY.plusSeconds(60));
        fixture.store.cleanupExpired();
        assertThat(fixture.store.size()).isEqualTo(2);
        fixture.refresh(refresh).expectStatus().isUnauthorized();
        fixture.controllerClient.get().uri("/auth/validate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.valid").isEqualTo(false);

        fixture.clock.set(EXPIRY.plusSeconds(60).plusNanos(1));
        fixture.store.cleanupExpired();
        assertThat(fixture.store.size()).isZero();
    }

    @Test
    void memoryLookupCleanupCountAndReuseShareTheInclusiveDeadline() {
        MutableClock clock = new MutableClock(ISSUED_AT);
        InMemoryTokenRevocationStore store = new InMemoryTokenRevocationStore(clock);
        store.revoke("expires-soon", EXPIRY);
        store.revoke("survives", EXPIRY.plusSeconds(300));

        clock.set(EXPIRY.plusSeconds(60));
        store.cleanupExpired();
        assertThat(store.isRevoked("expires-soon")).isTrue();
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.revokeIfActive("expires-soon", EXPIRY.plusSeconds(300))).isFalse();

        clock.set(EXPIRY.plusSeconds(60).plusNanos(1));
        assertThat(store.isRevoked("expires-soon")).isFalse();
        store.cleanupExpired();
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.isRevoked("survives")).isTrue();
        assertThat(store.revokeIfActive("expires-soon", EXPIRY)).isFalse();
        assertThat(store.revokeIfActive("expires-soon", EXPIRY.plusSeconds(300))).isTrue();
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void refreshAcceptedAtDeadlineCannotBeConsumedAfterDeadline() {
        Fixture fixture = new Fixture();
        String refresh = fixture.token("refresh");
        fixture.clock.set(EXPIRY.plusSeconds(60));
        Jwt accepted = fixture.jwtService.validateToken(refresh);

        fixture.clock.set(EXPIRY.plusSeconds(60).plusNanos(1));
        assertThat(fixture.blacklist.consumeTokenForOneTimeUse(accepted.getId(), accepted.getExpiresAt())).isFalse();
        assertThat(fixture.store.revokeIfActive("new-already-expired-id", EXPIRY)).isFalse();
        assertThat(fixture.store.size()).isZero();
    }

    @Test
    void revokedAccessCrossingDeadlineDuringLookupCannotAuthenticate() {
        Fixture fixture = new Fixture();
        String access = fixture.token("access");
        fixture.revoke(access);
        fixture.clock.set(EXPIRY.plusSeconds(60));
        fixture.beforeLookup = () -> fixture.clock.set(EXPIRY.plusSeconds(60).plusNanos(1));

        fixture.protectedClient.get().uri("/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void refreshCrossingDeadlineDuringConsumptionCannotIssueTokenPair() {
        Fixture fixture = new Fixture();
        String refresh = fixture.token("refresh");
        fixture.clock.set(EXPIRY.plusSeconds(60));
        fixture.afterConsumption = () -> fixture.clock.set(EXPIRY.plusSeconds(60).plusNanos(1));

        fixture.refresh(refresh).expectStatus().isUnauthorized().expectBody().isEmpty();
    }

    @Test
    void consumedRefreshPausedAtDeadlineCannotReplayAfterCleanup() {
        Fixture fixture = new Fixture();
        String refresh = fixture.token("refresh");
        fixture.refresh(refresh).expectStatus().isOk();
        fixture.clock.set(EXPIRY.plusSeconds(60));
        fixture.beforeConsumption = () -> fixture.clock.afterNextSample = () -> {
            fixture.clock.set(EXPIRY.plusSeconds(60).plusNanos(1));
            fixture.store.cleanupExpired();
        };

        fixture.refresh(refresh).expectStatus().isUnauthorized().expectBody().isEmpty();
    }

    @Test
    void memoryConsumptionWithStaleCutoffCannotWinAfterCleanup() {
        Fixture fixture = new Fixture();
        fixture.store.revoke("previously-consumed", EXPIRY);
        fixture.clock.set(EXPIRY.plusSeconds(60));
        fixture.clock.afterNextSample = () -> {
            fixture.clock.set(EXPIRY.plusSeconds(60).plusNanos(1));
            fixture.store.cleanupExpired();
        };

        assertThat(fixture.store.revokeIfActive("previously-consumed", EXPIRY)).isFalse();
        assertThat(fixture.store.size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {30, 60})
    void firstMemoryRefreshConsumptionHasExactlyOneWinnerInGrace(long secondsAfterExpiry) throws Exception {
        MutableClock clock = new MutableClock(EXPIRY.plusSeconds(secondsAfterExpiry));
        InMemoryTokenRevocationStore store = new InMemoryTokenRevocationStore(clock);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CyclicBarrier start = new CyclicBarrier(8);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int participant = 0; participant < 8; participant++) {
                results.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return store.revokeIfActive("concurrent-grace-refresh", EXPIRY);
                }));
            }
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(store.isRevoked("concurrent-grace-refresh")).isTrue();
            assertThat(store.size()).isEqualTo(1);
            assertThat(store.revokeIfActive("concurrent-grace-refresh", EXPIRY)).isFalse();
            clock.set(EXPIRY.plusSeconds(60).plusNanos(1));
            assertThat(store.revokeIfActive("concurrent-grace-refresh", EXPIRY)).isFalse();
            assertThat(store.size()).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static String sign(JwtClaimsSet claims) {
        SecretKeySpec secret = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(secret))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
                .getTokenValue();
    }

    private static final class Fixture {
        private final MutableClock clock = new MutableClock(ISSUED_AT);
        private final JwtService jwtService;
        private Runnable beforeLookup = () -> {};
        private Runnable beforeConsumption = () -> {};
        private Runnable afterConsumption = () -> {};
        private final InMemoryTokenRevocationStore store = new InMemoryTokenRevocationStore(clock) {
            @Override
            public boolean isRevoked(String tokenId) {
                beforeLookup.run();
                return super.isRevoked(tokenId);
            }

            @Override
            public boolean revokeIfActive(String tokenId, Instant expiresAt) {
                beforeConsumption.run();
                boolean consumed = super.revokeIfActive(tokenId, expiresAt);
                if (consumed) {
                    afterConsumption.run();
                }
                return consumed;
            }
        };
        private final TokenBlacklistService blacklist = new TokenBlacklistService(store);
        private final WebTestClient controllerClient;
        private final WebTestClient securedClient;
        private final WebTestClient protectedClient;

        @SuppressWarnings("unchecked")
        private Fixture() {
            JwtProperties jwtProperties = new JwtProperties();
            jwtProperties.setSecret(SECRET);
            jwtProperties.setIssuer("lifetime-test");
            jwtProperties.setAccessTokenExpiry(300);
            jwtProperties.setRefreshTokenExpiry(300);
            jwtProperties.setEnabled(true);
            jwtService = new JwtService(jwtProperties, clock);
            ApiKeyProperties.ApiKeyClient client = new ApiKeyProperties.ApiKeyClient();
            client.setKey("synthetic-lifetime-api-key");
            client.setClientId(CLIENT_ID);
            client.setScopes(List.of("mcp:tools:list"));
            ApiKeyProperties apiKeys = new ApiKeyProperties();
            apiKeys.setApiKeys(List.of(client));
            AuthController controller = new AuthController(jwtService, apiKeys, blacklist);
            controllerClient = WebTestClient.bindToController(controller).build();
            ObjectProvider<JwtService> jwtProvider = mock(ObjectProvider.class);
            when(jwtProvider.getIfAvailable()).thenReturn(jwtService);
            ObjectProvider<TokenBlacklistService> blacklistProvider = mock(ObjectProvider.class);
            when(blacklistProvider.getIfAvailable()).thenReturn(blacklist);
            ObjectProvider<ObjectMapper> mapperProvider = mock(ObjectProvider.class);
            when(mapperProvider.getIfAvailable(any())).thenReturn(new ObjectMapper());
            SecurityConfig security = new SecurityConfig(jwtProperties, apiKeys, jwtProvider, blacklistProvider,
                    mapperProvider, mock(ObservabilityService.class), mock(AuthEndpointRateLimiter.class));
            ReflectionTestUtils.setField(security, "securityModeConfig", "jwt");
            ReflectionTestUtils.setField(security, "securityEnabled", true);
            securedClient = WebTestClient.bindToController(controller)
                    .webFilter(security.authenticationFilter()).build();
            protectedClient = WebTestClient.bindToWebHandler(exchange -> exchange.getResponse().setComplete())
                    .webFilter(security.authenticationFilter()).build();
        }

        private String token(String type) {
            return "refresh".equals(type) ? jwtService.generateRefreshToken(CLIENT_ID)
                    : jwtService.generateAccessToken(CLIENT_ID, List.of("mcp:tools:list"));
        }

        private void revoke(String token) {
            controllerClient.post().uri("/auth/revoke")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("token", token))
                    .exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.revoked").isEqualTo(true)
                    .jsonPath("$.expiresAt").isEqualTo(EXPIRY.toString());
        }

        private WebTestClient.ResponseSpec refresh(String token) {
            return controllerClient.post().uri("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("refreshToken", token)).exchange();
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        private Runnable afterNextSample;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            Instant sampled = now;
            Runnable action = afterNextSample;
            if (action != null) {
                afterNextSample = null;
                action.run();
            }
            return sampled;
        }
    }
}
