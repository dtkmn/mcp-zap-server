package mcp.server.zap.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Parameter;
import java.util.Arrays;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

class GuidedAuthSessionMcpToolsServiceTest {

    @Test
    void prepareSurfaceAcceptsOnlyProfileAndPolicyVisibleTarget() {
        var prepareTools = Arrays.stream(GuidedAuthSessionMcpToolsService.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .filter(method -> "zap_auth_session_prepare".equals(method.getAnnotation(Tool.class).name()))
                .toList();
        assertThat(prepareTools).hasSize(1);
        assertThat(prepareTools.get(0).getParameters())
                .extracting(Parameter::getName)
                .containsExactly("profileId", "targetUrl");

        GuidedAuthSessionService authSessionService = mock(GuidedAuthSessionService.class);
        GuidedAuthSessionMcpToolsService tools = new GuidedAuthSessionMcpToolsService(authSessionService);
        when(authSessionService.prepareSession("shop-staging", "https://shop.example.com/admin"))
                .thenReturn("prepared");

        String response = tools.prepareAuthSession("shop-staging", "https://shop.example.com/admin");

        assertThat(response).isEqualTo("prepared");
        verify(authSessionService).prepareSession("shop-staging", "https://shop.example.com/admin");
    }
}
