package mcp.server.zap.core.service;

import mcp.server.zap.core.exception.ZapApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Authentication;
import org.zaproxy.clientapi.gen.Context;
import org.zaproxy.clientapi.gen.Users;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextUserServiceTest {

    private Context context;
    private Users users;
    private Authentication authentication;
    private ContextUserService service;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        context = mock(Context.class);
        users = mock(Users.class);
        authentication = mock(Authentication.class);

        clientApi.context = context;
        clientApi.users = users;
        clientApi.authentication = authentication;

        service = new ContextUserService(clientApi);
    }

    @Test
    void listContextsReturnsDetailedContextSummaries() throws Exception {
        when(context.contextList()).thenReturn(list("contextList", element("context", "shop-auth")));
        when(context.context("shop-auth")).thenReturn(set("context", Map.of(
                "id", element("id", "7"),
                "inScope", element("inScope", "true")
        )));
        when(context.includeRegexs("shop-auth")).thenReturn(list("includeRegexs",
                element("regex", "https://shop.example.com/.*")));
        when(context.excludeRegexs("shop-auth")).thenReturn(list("excludeRegexs",
                element("regex", "https://shop.example.com/logout.*")));

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
    void upsertContextCreatesAndAppliesRegexesAndScope() throws Exception {
        when(context.contextList()).thenReturn(list("contextList"));
        when(context.context("api-auth")).thenReturn(set("context", Map.of(
                "id", element("id", "11"),
                "inScope", element("inScope", "false")
        )));
        when(context.includeRegexs("api-auth")).thenReturn(list("includeRegexs",
                element("regex", "https://api.example.com/.*")));
        when(context.excludeRegexs("api-auth")).thenReturn(list("excludeRegexs",
                element("regex", "https://api.example.com/logout.*")));

        Map<String, Object> result = service.upsertContext(
                "api-auth",
                List.of("https://api.example.com/.*"),
                List.of("https://api.example.com/logout.*"),
                true
        );

        assertEquals(Boolean.TRUE, result.get("created"));
        verify(context).newContext("api-auth");
        verify(context).setContextRegexs(
                "api-auth",
                "[\"https://api.example.com/.*\"]",
                "[\"https://api.example.com/logout.*\"]"
        );
        verify(context).setContextInScope("api-auth", "true");
    }

    @Test
    void listUsersReturnsContextUsers() throws Exception {
        when(users.usersList("9")).thenReturn(list("usersList",
                set("user", Map.of(
                        "id", element("id", "101"),
                        "name", element("name", "scan-bot"),
                        "enabled", element("enabled", "true")
                )),
                set("user", Map.of(
                        "id", element("id", "102"),
                        "name", element("name", "qa-bot"),
                        "enabled", element("enabled", "false")
                ))
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
    void upsertUserUpdatesExistingUserWithoutCreatingNewOne() throws Exception {
        when(users.usersList("5")).thenReturn(list("usersList",
                set("user", Map.of(
                        "id", element("id", "77"),
                        "name", element("name", "scan-user"),
                        "enabled", element("enabled", "false")
                ))
        ));
        when(users.getUserById("5", "77")).thenReturn(set("user", Map.of(
                "id", element("id", "77"),
                "name", element("name", "scan-user"),
                "enabled", element("enabled", "true")
        )));

        Map<String, Object> result = service.upsertUser(
                "5",
                "scan-user",
                "username=scan-user&password=s3cr3t",
                true
        );

        assertFalse((Boolean) result.get("created"));
        assertEquals("77", result.get("userId"));
        assertEquals(Boolean.TRUE, result.get("enabled"));

        verify(users, never()).newUser(anyString(), anyString());
        verify(users).setAuthenticationCredentials("5", "77", "username=scan-user&password=s3cr3t");
        verify(users).setUserEnabled("5", "77", "true");
    }

    @Test
    void upsertUserCreatesUserWhenMissing() throws Exception {
        when(users.usersList("3")).thenReturn(list("usersList"));
        when(users.newUser("3", "api-user")).thenReturn(element("userId", "88"));
        when(users.getUserById("3", "88")).thenReturn(set("user", Map.of(
                "id", element("id", "88"),
                "name", element("name", "api-user"),
                "enabled", element("enabled", "true")
        )));

        Map<String, Object> result = service.upsertUser("3", "api-user", null, true);

        assertTrue((Boolean) result.get("created"));
        assertEquals("88", result.get("userId"));
        assertEquals("api-user", result.get("userName"));
        assertNotNull(result.get("enabled"));
        verify(users).newUser("3", "api-user");
    }

    @Test
    void listContextsWrapsZapException() throws Exception {
        when(context.contextList()).thenThrow(new ClientApiException("boom"));
        assertThrowsExactly(ZapApiException.class, () -> service.listContexts());
    }

    @Test
    void configureContextAuthenticationAppliesMethodAndIndicators() throws Exception {
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

        verify(authentication).setAuthenticationMethod("1", "formBasedAuthentication", "loginUrl=https://app.example.com/login");
        verify(authentication).setLoggedInIndicator("1", ".*Logout.*");
        verify(authentication).setLoggedOutIndicator("1", ".*Login.*");
    }

    @Test
    void testUserAuthenticationReturnsDiagnostics() throws Exception {
        when(users.authenticateAsUser("1", "7")).thenReturn(set("auth", Map.of(
                "authSuccessful", element("authSuccessful", "true"),
                "message", element("message", "ok")
        )));
        when(users.getAuthenticationState("1", "7")).thenReturn(set("state", Map.of(
                "lastPollResult", element("lastPollResult", "true")
        )));

        Map<String, Object> result = service.testUserAuthentication("1", "7");

        assertEquals("1", result.get("contextId"));
        assertEquals("7", result.get("userId"));
        assertEquals(Boolean.TRUE, result.get("likelyAuthenticated"));
        assertTrue(result.get("authResponse").toString().contains("authSuccessful"));
        assertTrue(result.get("authState").toString().contains("lastPollResult"));
    }

    private ApiResponseElement element(String name, String value) {
        return new ApiResponseElement(name, value);
    }

    private ApiResponseList list(String name, ApiResponse... items) {
        return new ApiResponseList(name, items);
    }

    private ApiResponseSet set(String name, Map<String, ApiResponse> values) {
        return new ApiResponseSet(name, new LinkedHashMap<>(values));
    }
}
