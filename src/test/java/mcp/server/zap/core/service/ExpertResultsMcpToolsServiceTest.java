package mcp.server.zap.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ExpertResultsMcpToolsServiceTest {

    @ParameterizedTest
    @MethodSource("findingsReadSchemas")
    void findingsDiscoveryRequiresTargetAndPreservesExistingParameterRequirements(
            String toolName, List<String> properties, List<String> required) throws Exception {
        ExpertResultsMcpToolsService service = new ExpertResultsMcpToolsService(
                mock(FindingsService.class), mock(ReportService.class));
        ToolCallback callback = Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(service)
                        .build()
                        .getToolCallbacks())
                .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
        JsonNode schema = new ObjectMapper().readTree(callback.getToolDefinition().inputSchema());
        List<String> requiredProperties = new ArrayList<>();
        for (JsonNode property : schema.path("required")) {
            requiredProperties.add(property.asString());
        }

        assertThat(schema.path("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(properties);
        assertThat(requiredProperties).containsExactlyInAnyOrderElementsOf(required);
    }

    private static Stream<Arguments> findingsReadSchemas() {
        return Stream.of(
                Arguments.of("zap_alert_details", List.of("baseUrl", "pluginId", "alertName"), List.of("baseUrl")),
                Arguments.of("zap_alert_instances", List.of("baseUrl", "pluginId", "alertName", "limit"), List.of("baseUrl")),
                Arguments.of("zap_findings_snapshot", List.of("baseUrl"), List.of("baseUrl")),
                Arguments.of("zap_findings_diff", List.of("baseUrl", "baselineSnapshot", "maxGroups"),
                        List.of("baseUrl", "baselineSnapshot", "maxGroups")),
                Arguments.of("zap_get_findings_summary", List.of("baseUrl"), List.of("baseUrl"))
        );
    }
}
