package mcp.server.zap.core.service.authz;

import java.util.Arrays;
import java.util.List;
import mcp.server.zap.core.configuration.ToolAuthorizationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class GuidedToolActionsTest {
    private ToolAuthorizationProperties properties;
    private ToolAuthorizationService authorizationService;
    private GuidedToolActions actions;

    @BeforeEach
    void setUp() {
        properties = new ToolAuthorizationProperties();
        authorizationService = new ToolAuthorizationService(new ToolScopeRegistry(), properties);
        actions = new GuidedToolActions(authorizationService, true, "api-key");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesCurrentCallersGrantedScopesAndDoesNotCacheAcrossRequests() {
        useScopes("zap:scan:crawl:run", "zap:scan:read");
        assertThat(actions.canCall("zap_crawl_start")).isTrue();
        assertThat(actions.canCall("zap_crawl_status")).isTrue();
        assertThat(actions.canCall("zap_attack_start")).isFalse();
        useScopes("zap:scan:attack:run");
        assertThat(actions.canCall("zap_attack_start")).isTrue();
        assertThat(actions.canCall("zap_crawl_start")).isFalse();
    }

    @Test
    void wildcardFollowsOperatorPolicy() {
        useScopes("*");
        assertThat(actions.canCall("zap_attack_start")).isTrue();
        properties.setAllowWildcard(false);
        assertThat(actions.canCall("zap_attack_start")).isFalse();
        useScopes("*", "zap:scan:attack:run");
        assertThat(actions.canCall("zap_attack_start")).isTrue();
    }

    @Test
    void missingAuthenticationDoesNotInventPermissions() {
        SecurityContextHolder.clearContext();
        assertThat(actions.canCall("zap_crawl_status")).isFalse();
        assertThat(actions.canCall("zap_attack_start")).isFalse();
    }

    @Test
    void nonScopeAuthoritiesDoNotGrantToolPermissions() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "client", "credentials", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("zap:scan:attack:run"))));
        assertThat(actions.canCall("zap_attack_start")).isFalse();
    }

    @Test
    void jwtUsesEffectiveAuthoritiesRatherThanRawClaimsOrAnotherClientsRegistration() {
        Jwt jwt = Jwt.withTokenValue("opaque-token").header("alg", "none")
                .claim("sub", "jwt-client").claim("scope", "*").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_zap:scan:crawl:run"))));
        assertThat(actions.canCall("zap_crawl_start")).isTrue();
        assertThat(actions.canCall("zap_attack_start")).isFalse();
        assertThat(actions.canCall("zap_report_read")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ToolAuthorizationProperties.Mode.class, names = {"OFF", "WARN"})
    void nonEnforcingModesPreserveAvailableFollowups(ToolAuthorizationProperties.Mode mode) {
        properties.setMode(mode);
        assertThat(actions.canCall("zap_attack_start")).isTrue();
        assertThat(actions.canCall("zap_report_generate")).isTrue();
    }

    @Test
    void disabledSecurityPreservesFollowups() {
        assertThat(new GuidedToolActions(authorizationService, false, "api-key")
                .canCall("zap_attack_start")).isTrue();
        assertThat(new GuidedToolActions(authorizationService, true, " NONE ")
                .canCall("zap_report_read")).isTrue();
    }

    private void useScopes(String... scopes) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "client", "credentials", Arrays.stream(scopes)
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList()));
    }
}
