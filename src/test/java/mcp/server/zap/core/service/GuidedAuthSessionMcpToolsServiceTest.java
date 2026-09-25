package mcp.server.zap.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GuidedAuthSessionMcpToolsServiceTest {

    @Test
    void prepareToolExposesOnlyProfileAndTargetAndDelegatesSuppliedArguments() {
        GuidedAuthSessionService authSessionService = mock(GuidedAuthSessionService.class);
        GuidedAuthSessionMcpToolsService tools = new GuidedAuthSessionMcpToolsService(authSessionService);
        ToolCallback prepareTool = Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(tools)
                        .build()
                        .getToolCallbacks())
                .filter(callback -> "zap_auth_session_prepare".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode inputSchema = objectMapper.readTree(prepareTool.getToolDefinition().inputSchema());
        assertThat(inputSchema.path("properties").propertyNames())
                .containsExactlyInAnyOrder("profileId", "targetUrl");

        when(authSessionService.prepareSession("shop-staging", "https://shop.example.com/admin"))
                .thenReturn("prepared");

        String response = prepareTool.call("""
                {"profileId":"shop-staging","targetUrl":"https://shop.example.com/admin"}
                """);

        assertThat(objectMapper.readTree(response).asString()).isEqualTo("prepared");
        verify(authSessionService).prepareSession("shop-staging", "https://shop.example.com/admin");
    }
}
