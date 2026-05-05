package mcp.server.zap.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayExecutionBoundaryArchitectureTest {

    private static final Path ACTIVE_SCAN_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/ActiveScanService.java");
    private static final Path SPIDER_SCAN_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/SpiderScanService.java");
    private static final Path FINDINGS_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/FindingsService.java");
    private static final Path REPORT_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/ReportService.java");
    private static final Path CONTEXT_USER_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/ContextUserService.java");
    private static final Path OPEN_API_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/OpenApiService.java");
    private static final Path PASSIVE_SCAN_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/PassiveScanService.java");
    private static final Path AJAX_SPIDER_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/AjaxSpiderService.java");
    private static final Path AUTOMATION_PLAN_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/AutomationPlanService.java");
    private static final Path CORE_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/CoreService.java");
    private static final Path ZAP_INITIALIZATION_SERVICE =
            Path.of("src/main/java/mcp/server/zap/core/service/ZapInitializationService.java");
    private static final Path ZAP_HEALTH_INDICATOR =
            Path.of("src/main/java/mcp/server/zap/core/configuration/ZapHealthIndicator.java");
    private static final Path ZAP_SCAN_EXECUTION =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEngineScanExecution.java");
    private static final Path ZAP_FINDING_ACCESS =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEngineFindingAccess.java");
    private static final Path ZAP_REPORT_ACCESS =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEngineReportAccess.java");
    private static final Path ZAP_CONTEXT_ACCESS =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEngineContextAccess.java");
    private static final Path ZAP_API_IMPORT_ACCESS =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEngineApiImportAccess.java");
    private static final Path ZAP_PASSIVE_SCAN_ACCESS =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEnginePassiveScanAccess.java");
    private static final Path ZAP_AJAX_SPIDER_EXECUTION =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEngineAjaxSpiderExecution.java");
    private static final Path ZAP_AUTOMATION_ACCESS =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEngineAutomationAccess.java");
    private static final Path ZAP_INVENTORY_ACCESS =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEngineInventoryAccess.java");
    private static final Path ZAP_RUNTIME_ACCESS =
            Path.of("src/main/java/mcp/server/zap/core/gateway/ZapEngineRuntimeAccess.java");
    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path GATEWAY_PACKAGE =
            Path.of("src/main/java/mcp/server/zap/core/gateway");
    private static final Path ZAP_API_CONFIG =
            Path.of("src/main/java/mcp/server/zap/core/configuration/ZapApiConfig.java");

    @Test
    void directScanServicesUseGatewayExecutionBoundaryInsteadOfZapClientApi() throws IOException {
        assertThat(Files.readString(ACTIVE_SCAN_SERVICE))
                .contains("EngineScanExecution")
                .doesNotContain("org.zaproxy.clientapi.core")
                .doesNotContain("ClientApi");

        assertThat(Files.readString(SPIDER_SCAN_SERVICE))
                .contains("EngineScanExecution")
                .doesNotContain("org.zaproxy.clientapi.core")
                .doesNotContain("ClientApi");
    }

    @Test
    void readImportReportAndContextServicesUseGatewayBoundariesInsteadOfZapClientApi() throws IOException {
        assertServiceUsesBoundaryWithoutZap(FINDINGS_SERVICE, "EngineFindingAccess");
        assertServiceUsesBoundaryWithoutZap(REPORT_SERVICE, "EngineReportAccess");
        assertServiceUsesBoundaryWithoutZap(CONTEXT_USER_SERVICE, "EngineContextAccess");
        assertServiceUsesBoundaryWithoutZap(OPEN_API_SERVICE, "EngineApiImportAccess");
    }

    @Test
    void remainingProductAndRuntimeServicesUseGatewayBoundariesInsteadOfZapClientApi() throws IOException {
        assertServiceUsesBoundaryWithoutZap(PASSIVE_SCAN_SERVICE, "EnginePassiveScanAccess");
        assertServiceUsesBoundaryWithoutZap(AJAX_SPIDER_SERVICE, "EngineAjaxSpiderExecution");
        assertServiceUsesBoundaryWithoutZap(AUTOMATION_PLAN_SERVICE, "EngineAutomationAccess");
        assertServiceUsesBoundaryWithoutZap(CORE_SERVICE, "EngineInventoryAccess");
        assertServiceUsesBoundaryWithoutZap(ZAP_INITIALIZATION_SERVICE, "EngineRuntimeAccess");
        assertServiceUsesBoundaryWithoutZap(ZAP_HEALTH_INDICATOR, "EngineRuntimeAccess");
    }

    @Test
    void zapScanExecutionIsTheZapClientApiBoundaryForDirectScanServices() throws IOException {
        assertThat(Files.readString(ZAP_SCAN_EXECUTION))
                .contains("ClientApi")
                .contains("zap.spider")
                .contains("zap.ascan");
    }

    @Test
    void zapAccessAdaptersOwnZapClientApiForReadImportReportAndContextServices() throws IOException {
        assertThat(Files.readString(ZAP_FINDING_ACCESS))
                .contains("ClientApi")
                .contains("zap.alert");
        assertThat(Files.readString(ZAP_REPORT_ACCESS))
                .contains("ClientApi")
                .contains("zap.reports");
        assertThat(Files.readString(ZAP_CONTEXT_ACCESS))
                .contains("ClientApi")
                .contains("zap.context")
                .contains("zap.users")
                .contains("zap.authentication");
        assertThat(Files.readString(ZAP_API_IMPORT_ACCESS))
                .contains("ClientApi")
                .contains("zap.openapi")
                .contains("zap.graphql")
                .contains("zap.soap");
    }

    @Test
    void zapAccessAdaptersOwnZapClientApiForRemainingProductAndRuntimeServices() throws IOException {
        assertThat(Files.readString(ZAP_PASSIVE_SCAN_ACCESS))
                .contains("ClientApi")
                .contains("zap.pscan");
        assertThat(Files.readString(ZAP_AJAX_SPIDER_EXECUTION))
                .contains("ClientApi")
                .contains("zap.ajaxSpider")
                .contains("zap.core");
        assertThat(Files.readString(ZAP_AUTOMATION_ACCESS))
                .contains("ClientApi")
                .contains("zap.automation");
        assertThat(Files.readString(ZAP_INVENTORY_ACCESS))
                .contains("ClientApi")
                .contains("zap.core");
        assertThat(Files.readString(ZAP_RUNTIME_ACCESS))
                .contains("ClientApi")
                .contains("zap.core")
                .contains("zap.network");
    }

    @Test
    void zapClientApiImportsStayInsideGatewayAdaptersOrWiringConfig() throws IOException {
        List<Path> offenders;
        try (var files = Files.walk(MAIN_JAVA)) {
            offenders = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> fileContains(path, "org.zaproxy.clientapi.core"))
                    .filter(path -> !path.startsWith(GATEWAY_PACKAGE))
                    .filter(path -> !path.equals(ZAP_API_CONFIG))
                    .toList();
        }

        assertThat(offenders).isEmpty();
    }

    private void assertServiceUsesBoundaryWithoutZap(Path path, String boundaryName) throws IOException {
        assertThat(Files.readString(path))
                .contains(boundaryName)
                .doesNotContain("org.zaproxy.clientapi.core")
                .doesNotContain("ClientApi");
    }

    private boolean fileContains(Path path, String text) {
        try {
            return Files.readString(path).contains(text);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + path, e);
        }
    }
}
