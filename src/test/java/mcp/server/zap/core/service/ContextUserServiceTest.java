package mcp.server.zap.core.service;

import java.util.List;
import java.util.Map;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineContextAccess;
import mcp.server.zap.core.gateway.EngineContextAccess.AuthenticationConfigRequest;
import mcp.server.zap.core.gateway.EngineContextAccess.AuthenticationConfigResult;
import mcp.server.zap.core.gateway.EngineContextAccess.AuthenticationDiagnostics;
import mcp.server.zap.core.gateway.EngineContextAccess.ContextMutation;
import mcp.server.zap.core.gateway.EngineContextAccess.ContextMutationResult;
import mcp.server.zap.core.gateway.EngineContextAccess.ContextSnapshot;
import mcp.server.zap.core.gateway.EngineContextAccess.UserMutation;
import mcp.server.zap.core.gateway.EngineContextAccess.UserMutationResult;
import mcp.server.zap.core.gateway.EngineContextAccess.UserSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextUserServiceTest {

    private EngineContextAccess contextAccess;
    private ContextUserService service;

    @BeforeEach
    void setup() {
        contextAccess = mock(EngineContextAccess.class);
        service = new ContextUserService(contextAccess);
    }

    @Test
    void listContextsReturnsDetailedContextSummaries() {
        when(contextAccess.listContexts()).thenReturn(List.of(new ContextSnapshot(
                "shop-auth",
                "7",
                Boolean.TRUE,
                List.of("https://shop.example.com/.*"),
                List.of("https://shop.example.com/logout.*")
        )));

        List<Map<String, Object>> result = service.listContexts();

        assertEquals(1, result.size());
        Map<String, Object> first = result.getFirst();
        assertEquals("shop-auth", first.get("contextName"));
        assertEquals("7", first.get("contextId"));
        assertEquals(Boolean.TRUE, first.get("inScope"));
        assertEquals(List.of("https://shop.example.com/.*"), first.get("includeRegexes"));
        assertEquals(List.of("https://shop.example.com/logout.*"), first.get("excludeRegexes"));
    }

    @Test
    void upsertContextValidatesAndDelegatesToBoundary() {
        ContextMutation mutation = new ContextMutation(
                "api-auth",
                List.of("https://api.example.com/.*"),
                List.of("https://api.example.com/logout.*"),
                true
        );
        when(contextAccess.upsertContext(mutation)).thenReturn(new ContextMutationResult(new ContextSnapshot(
                "api-auth",
                "11",
                Boolean.TRUE,
                mutation.includeRegexes(),
                mutation.excludeRegexes()
        ), true));

        Map<String, Object> result = service.upsertContext(
                "api-auth",
                List.of("https://api.example.com/.*"),
                List.of("https://api.example.com/logout.*"),
                true
        );

        assertEquals(Boolean.TRUE, result.get("created"));
        assertEquals("11", result.get("contextId"));
        verify(contextAccess).upsertContext(mutation);
    }

    @Test
    void listUsersReturnsContextUsers() {
        when(contextAccess.listUsers("9")).thenReturn(List.of(
                new UserSnapshot("9", "101", "scan-bot", Boolean.TRUE),
                new UserSnapshot("9", "102", "qa-bot", Boolean.FALSE)
        ));

        List<Map<String, Object>> result = service.listUsers("9");

        assertEquals(2, result.size());
        assertEquals("101", result.getFirst().get("userId"));
        assertEquals("scan-bot", result.get(0).get("userName"));
        assertEquals(Boolean.TRUE, result.get(0).get("enabled"));
        assertEquals("102", result.get(1).get("userId"));
        assertEquals(Boolean.FALSE, result.get(1).get("enabled"));
    }

    @Test
    void upsertUserDelegatesWithNormalizedCredentials() {
        UserMutation mutation = new UserMutation("5", "scan-user", "username=scan-user&password=s3cr3t", true);
        when(contextAccess.upsertUser(mutation)).thenReturn(new UserMutationResult(
                "5",
                "77",
                "scan-user",
                Boolean.TRUE,
                false
        ));

        Map<String, Object> result = service.upsertUser(
                "5",
                "scan-user",
                " username=scan-user&password=s3cr3t ",
                true
        );

        assertEquals(Boolean.FALSE, result.get("created"));
        assertEquals("77", result.get("userId"));
        assertEquals(Boolean.TRUE, result.get("enabled"));
        verify(contextAccess).upsertUser(mutation);
    }

    @Test
    void listContextsWrapsBoundaryException() {
        when(contextAccess.listContexts())
                .thenThrow(new ZapApiException("Failed to list contexts", new RuntimeException("boom")));

        assertThrowsExactly(ZapApiException.class, () -> service.listContexts());
    }

    @Test
    void configureContextAuthenticationValidatesIndicatorsAndDelegates() {
        AuthenticationConfigRequest request = new AuthenticationConfigRequest(
                "1",
                "formBasedAuthentication",
                "loginUrl=https://app.example.com/login",
                ".*Logout.*",
                ".*Login.*"
        );
        when(contextAccess.configureContextAuthentication(request)).thenReturn(new AuthenticationConfigResult(
                "1",
                "formBasedAuthentication",
                true,
                true,
                true
        ));

        Map<String, Object> result = service.configureContextAuthentication(
                "1",
                "formBasedAuthentication",
                "loginUrl=https://app.example.com/login",
                ".*Logout.*",
                ".*Login.*"
        );

        assertEquals("1", result.get("contextId"));
        assertEquals("formBasedAuthentication", result.get("authMethodName"));
        assertEquals(Boolean.TRUE, result.get("authMethodConfigParamsProvided"));
        assertEquals(Boolean.TRUE, result.get("loggedInIndicatorSet"));
        assertEquals(Boolean.TRUE, result.get("loggedOutIndicatorSet"));
        verify(contextAccess).configureContextAuthentication(request);
    }

    @Test
    void testUserAuthenticationReturnsDiagnostics() {
        when(contextAccess.testUserAuthentication("1", "7")).thenReturn(new AuthenticationDiagnostics(
                "1",
                "7",
                Boolean.TRUE,
                "authSuccessful=true",
                "lastPollResult=true"
        ));

        Map<String, Object> result = service.testUserAuthentication("1", "7");

        assertEquals("1", result.get("contextId"));
        assertEquals("7", result.get("userId"));
        assertEquals(Boolean.TRUE, result.get("likelyAuthenticated"));
        assertTrue(result.get("authResponse").toString().contains("authSuccessful"));
        assertTrue(result.get("authState").toString().contains("lastPollResult"));
    }
}
