package mcp.server.zap.core.service;

import java.util.Locale;
import mcp.server.zap.core.gateway.ArtifactRecord;
import mcp.server.zap.core.gateway.EngineAdapter;
import mcp.server.zap.core.gateway.EngineCapability;
import mcp.server.zap.core.gateway.FindingRecord;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.TargetDescriptor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Intent-first MCP facade that exposes a small guided tool surface.
 */
@Service
public class GuidedSecurityToolsService {
    private static final String REPORT_FORMAT_HTML = "html";
    private static final String REPORT_FORMAT_JSON = "json";
    private static final String NEXT_ACTIONS_HEADER = "Next Actions:";

    private final GuidedScanWorkflowService guidedScanWorkflowService;
    private final ReportService reportService;
    private final FindingsService findingsService;
    private final OpenApiService openApiService;
    private final EngineAdapter engineAdapter;
    private final GatewayRecordFactory gatewayRecordFactory;

    public GuidedSecurityToolsService(GuidedScanWorkflowService guidedScanWorkflowService,
                                      ReportService reportService,
                                      FindingsService findingsService,
                                      OpenApiService openApiService,
                                      EngineAdapter engineAdapter,
                                      GatewayRecordFactory gatewayRecordFactory) {
        this.guidedScanWorkflowService = guidedScanWorkflowService;
        this.reportService = reportService;
        this.findingsService = findingsService;
        this.openApiService = openApiService;
        this.engineAdapter = engineAdapter;
        this.gatewayRecordFactory = gatewayRecordFactory;
    }

    @Tool(
            name = "zap_target_import",
            description = "Import an API definition into ZAP using one guided entrypoint for OpenAPI, GraphQL, or SOAP."
    )
    public String importTargetDefinition(
            @ToolParam(description = "Definition type: openapi, graphql, or soap") String definitionType,
            @ToolParam(description = "Source kind: url or file") String sourceKind,
            @ToolParam(description = "Definition source URL or file path") String source,
            @ToolParam(required = false, description = "Optional GraphQL endpoint URL when definitionType is graphql") String endpointUrl,
            @ToolParam(required = false, description = "Optional host override when definitionType is openapi") String hostOverride
    ) {
        gatewayRecordFactory.requireCapability(engineAdapter, EngineCapability.TARGET_IMPORT, "target import");
        String normalizedType = normalizeDefinitionType(definitionType);
        String normalizedSourceKind = normalizeSourceKind(sourceKind);
        String normalizedSource = requireText(source, "source");
        String delegateResponse = importDefinition(
                normalizedType,
                normalizedSourceKind,
                normalizedSource,
                endpointUrl,
                hostOverride
        );
        return formatImportMessage(normalizedType, normalizedSourceKind, normalizedSource, delegateResponse);
    }

    @Tool(
            name = "zap_crawl_start",
            description = "Start a guided crawl for a target host or root URL. The server decides direct versus queued execution from deployment topology. Use strategy=http for traditional server-rendered sites, strategy=browser for SPAs, login-heavy flows, or JavaScript-driven apps, and strategy=auto when you want the service to pick the default crawl engine. When authSessionId is supplied, guided crawl currently supports prepared form-login sessions on the HTTP spider path only."
    )
    public String startCrawl(
            @ToolParam(description = "Target host or root URL to crawl, for example https://app.example.com or https://app.example.com/admin") String targetUrl,
            @ToolParam(required = false, description = "Optional crawl strategy. Use auto to prefer the default guided engine, http for traditional pages and simple link discovery, or browser for SPAs, authenticated flows, and JavaScript-heavy navigation.") String strategy,
            @ToolParam(required = false, description = "Optional idempotency key used only when guided execution selects queued mode; ignored in direct mode.") String idempotencyKey,
            @ToolParam(required = false, description = "Optional prepared auth session ID from zap_auth_session_prepare. Guided crawl currently accepts form-login sessions only and rejects browser strategy when auth is supplied.") String authSessionId
    ) {
        return guidedScanWorkflowService.startCrawl(targetUrl, strategy, idempotencyKey, authSessionId);
    }

    @Tool(
            name = "zap_crawl_status",
            description = "Get status for a guided crawl operation."
    )
    public String getCrawlStatus(
            @ToolParam(description = "Guided crawl operation ID returned by zap_crawl_start") String operationId
    ) {
        return guidedScanWorkflowService.getCrawlStatus(operationId);
    }

    @Tool(
            name = "zap_crawl_stop",
            description = "Stop a guided crawl operation."
    )
    public String stopCrawl(
            @ToolParam(description = "Guided crawl operation ID returned by zap_crawl_start") String operationId
    ) {
        return guidedScanWorkflowService.stopCrawl(operationId);
    }

    @Tool(
            name = "zap_attack_start",
            description = "Start a guided active scan for a specific target host or base URL after crawl/import setup. The server decides direct versus queued execution from deployment topology. When authSessionId is supplied, guided attack currently accepts prepared form-login sessions only."
    )
    public String startAttack(
            @ToolParam(description = "Target host or base URL to attack, for example https://app.example.com or https://app.example.com/api") String targetUrl,
            @ToolParam(required = false, description = "Optional recurse flag (default: true)") String recurse,
            @ToolParam(required = false, description = "Optional active-scan policy name when you need a non-default rule set") String policy,
            @ToolParam(required = false, description = "Optional idempotency key used only when guided execution selects queued mode; ignored in direct mode.") String idempotencyKey,
            @ToolParam(required = false, description = "Optional prepared auth session ID from zap_auth_session_prepare. Guided attack currently accepts form-login sessions only.") String authSessionId
    ) {
        return guidedScanWorkflowService.startAttack(targetUrl, recurse, policy, idempotencyKey, authSessionId);
    }

    @Tool(
            name = "zap_attack_status",
            description = "Get status for a guided active-scan operation."
    )
    public String getAttackStatus(
            @ToolParam(description = "Guided attack operation ID returned by zap_attack_start") String operationId
    ) {
        return guidedScanWorkflowService.getAttackStatus(operationId);
    }

    @Tool(
            name = "zap_attack_stop",
            description = "Stop a guided active-scan operation."
    )
    public String stopAttack(
            @ToolParam(description = "Guided attack operation ID returned by zap_attack_start") String operationId
    ) {
        return guidedScanWorkflowService.stopAttack(operationId);
    }

    @Tool(
            name = "zap_findings_summary",
            description = "Get the first-pass findings view after a scan or passive-scan wait. This returns a concise grouped risk summary for fast triage. Use baseUrl to scope results to a specific host or path."
    )
    public String getGuidedFindingsSummary(
            @ToolParam(required = false, description = "Optional base URL filter to scope findings to a specific host or path, for example https://app.example.com/admin") String baseUrl
    ) {
        gatewayRecordFactory.requireCapability(engineAdapter, EngineCapability.FINDINGS_READ, "findings read");
        String normalizedBaseUrl = trimToEmpty(baseUrl);
        FindingRecord findingRecord = gatewayRecordFactory.findingSummary(
                engineAdapter,
                gatewayRecordFactory.optionalTarget(normalizedBaseUrl, TargetDescriptor.Kind.WEB),
                "summary"
        );
        String findingsSummary = findingsService.getFindingsSummary(normalizedBaseUrl);
        return formatGuidedFindingsSummary(targetScope(findingRecord.target()), findingsSummary);
    }

    @Tool(
            name = "zap_findings_details",
            description = "Drill into findings after reading the summary. By default this returns grouped details for matching alerts. Set includeInstances=true when you need bounded raw alert occurrences with concrete URLs, params, evidence, or attack samples."
    )
    public String getGuidedFindingsDetails(
            @ToolParam(required = false, description = "Optional base URL filter to scope findings to one host or path") String baseUrl,
            @ToolParam(required = false, description = "Optional plugin ID filter when you already know the ZAP alert/plugin identifier to inspect") String pluginId,
            @ToolParam(required = false, description = "Optional alert name filter when you want one alert family only") String alertName,
            @ToolParam(required = false, description = "Optional true to include bounded raw instances with concrete URLs and evidence; false returns grouped detail blocks") Boolean includeInstances,
            @ToolParam(required = false, description = "Optional instance limit used only when includeInstances is true") Integer limit
    ) {
        gatewayRecordFactory.requireCapability(engineAdapter, EngineCapability.FINDINGS_READ, "findings read");
        String normalizedBaseUrl = trimToEmpty(baseUrl);
        String normalizedPluginId = trimToEmpty(pluginId);
        String normalizedAlertName = trimToEmpty(alertName);
        TargetDescriptor target = gatewayRecordFactory.optionalTarget(normalizedBaseUrl, TargetDescriptor.Kind.WEB);
        String findingsDetails = Boolean.TRUE.equals(includeInstances)
                ? findingsService.getAlertInstances(normalizedBaseUrl, normalizedPluginId, normalizedAlertName, limit)
                : findingsService.getAlertDetails(normalizedBaseUrl, normalizedPluginId, normalizedAlertName);
        return formatGuidedFindingsDetails(
                targetScope(target),
                normalizedPluginId,
                normalizedAlertName,
                includeInstances,
                limit,
                findingsDetails
        );
    }

    @Tool(
            name = "zap_report_generate",
            description = "Generate a human-shareable report artifact using guided defaults. Use this after passive scan backlog drains when you want an export or handoff artifact; use findings summary/details for interactive triage."
    )
    public String generateGuidedReport(
            @ToolParam(required = false, description = "Optional base URL filter to include only one host or path in the report") String baseUrl,
            @ToolParam(required = false, description = "Optional report format: html for human reading or json for machine processing") String format,
            @ToolParam(required = false, description = "Optional report theme: light or dark") String theme
    ) {
        gatewayRecordFactory.requireCapability(engineAdapter, EngineCapability.REPORT_GENERATE, "report generation");
        String normalizedFormat = normalizeReportFormat(format);
        String normalizedTheme = hasText(theme) ? theme.trim() : "light";
        String normalizedBaseUrl = trimToEmpty(baseUrl);
        TargetDescriptor target = gatewayRecordFactory.optionalTarget(normalizedBaseUrl, TargetDescriptor.Kind.WEB);
        String reportPath = reportService.generateReport(
                reportTemplateFor(normalizedFormat),
                normalizedTheme,
                normalizedBaseUrl
        );
        ArtifactRecord artifact = gatewayRecordFactory.reportArtifact(engineAdapter, target, reportPath, normalizedFormat);
        return new StringBuilder()
                .append("Guided report generated.\n")
                .append("Format: ").append(normalizedFormat).append('\n')
                .append("Theme: ").append(normalizedTheme).append('\n')
                .append("Scope: ").append(targetScope(artifact.target())).append('\n')
                .append("Path: ").append(artifact.location()).append('\n')
                .append(reportNextActions(targetScope(artifact.target())))
                .toString();
    }

    @Tool(
            name = "zap_report_read",
            description = "Read a generated report artifact back through MCP after zap_report_generate, without manual filesystem access."
    )
    public String readGuidedReport(
            @ToolParam(description = "Report path returned by zap_report_generate") String reportPath,
            @ToolParam(required = false, description = "Maximum characters to return (optional, default: 20000, max: 200000)") Integer maxChars
    ) {
        String normalizedReportPath = requireText(reportPath, "reportPath");
        String report = reportService.readReport(normalizedReportPath, maxChars);
        return new StringBuilder()
                .append("Guided report readback.\n")
                .append("Path: ").append(normalizedReportPath).append('\n')
                .append("Use: review the generated artifact before attaching it to internal or customer-facing evidence.\n")
                .append(NEXT_ACTIONS_HEADER).append('\n')
                .append("- Internal evidence: call zap_scan_history_release_evidence for the same target or evidence window.\n")
                .append("- Customer summary: call zap_scan_history_customer_handoff only after reviewing report content and release-evidence warnings.\n\n")
                .append(report)
                .toString();
    }

    private String importDefinition(String definitionType,
                                    String sourceKind,
                                    String source,
                                    String endpointUrl,
                                    String hostOverride) {
        return switch (definitionType) {
            case "openapi" -> switch (sourceKind) {
                case "url" -> openApiService.importOpenApiSpec(source, hostOverride);
                case "file" -> openApiService.importOpenApiSpecFile(source, hostOverride);
                default -> throw new IllegalStateException("Unexpected OpenAPI source kind: " + sourceKind);
            };
            case "graphql" -> {
                String normalizedEndpointUrl = requireText(endpointUrl, "endpointUrl");
                yield switch (sourceKind) {
                    case "url" -> openApiService.importGraphqlSchemaUrl(normalizedEndpointUrl, source);
                    case "file" -> openApiService.importGraphqlSchemaFile(normalizedEndpointUrl, source);
                    default -> throw new IllegalStateException("Unexpected GraphQL source kind: " + sourceKind);
                };
            }
            case "soap" -> switch (sourceKind) {
                case "url" -> openApiService.importSoapWsdlUrl(source);
                case "file" -> openApiService.importSoapWsdlFile(source);
                default -> throw new IllegalStateException("Unexpected SOAP source kind: " + sourceKind);
            };
            default -> throw new IllegalStateException("Unexpected definition type: " + definitionType);
        };
    }

    private String formatImportMessage(String definitionType,
                                       String sourceKind,
                                       String source,
                                       String delegateResponse) {
        return new StringBuilder()
                .append("Guided target import completed.\n")
                .append("Definition Type: ").append(definitionType).append('\n')
                .append("Source Kind: ").append(sourceKind).append('\n')
                .append("Source: ").append(source).append('\n')
                .append('\n')
                .append(delegateResponse)
                .toString();
    }

    private String formatGuidedFindingsSummary(String baseUrl, String delegateResponse) {
        return new StringBuilder()
                .append("Guided findings summary.\n")
                .append("Scope: ").append(hasText(baseUrl) ? baseUrl : "All targets").append('\n')
                .append("Use: first-pass triage before drilldown or report generation.\n")
                .append(NEXT_ACTIONS_HEADER).append('\n')
                .append("- Drill down: call zap_findings_details for one alert family or concrete instances when you need evidence.\n")
                .append("- Report: call zap_report_generate after passive analysis has drained and the summary is ready to share.\n\n")
                .append(delegateResponse)
                .toString();
    }

    private String formatGuidedFindingsDetails(String baseUrl,
                                               String pluginId,
                                               String alertName,
                                               Boolean includeInstances,
                                               Integer limit,
                                               String delegateResponse) {
        StringBuilder output = new StringBuilder()
                .append("Guided findings details.\n")
                .append("Scope: ").append(hasText(baseUrl) ? baseUrl : "All targets").append('\n')
                .append("Mode: ").append(Boolean.TRUE.equals(includeInstances) ? "raw instances" : "grouped details").append('\n');
        if (hasText(pluginId)) {
            output.append("Plugin ID Filter: ").append(pluginId).append('\n');
        }
        if (hasText(alertName)) {
            output.append("Alert Name Filter: ").append(alertName).append('\n');
        }
        if (Boolean.TRUE.equals(includeInstances) && limit != null) {
            output.append("Requested Limit: ").append(limit).append('\n');
        }
        output.append("Use: ")
                .append(Boolean.TRUE.equals(includeInstances)
                        ? "inspect concrete URLs, params, evidence, and attack samples."
                        : "inspect grouped detail before expanding into raw per-occurrence evidence.")
                .append('\n')
                .append(NEXT_ACTIONS_HEADER)
                .append('\n')
                .append("- Report: call zap_report_generate when you need a human-shareable artifact.")
                .append('\n')
                .append("- Handoff: after a report exists, call zap_scan_history_release_evidence for internal review, then zap_scan_history_customer_handoff for the curated external summary.")
                .append("\n\n")
                .append(delegateResponse);
        return output.toString();
    }

    private String reportNextActions(String scope) {
        String targetAdvice = hasText(scope) && !"All targets".equals(scope)
                ? " with target filter " + scope
                : " with a target filter when you need scoped evidence";
        return new StringBuilder(NEXT_ACTIONS_HEADER)
                .append('\n')
                .append("- Report readback: call zap_report_read with the Path above before creating evidence or sharing the artifact.\n")
                .append("- Internal evidence: call zap_scan_history_release_evidence").append(targetAdvice).append(".\n")
                .append("- Customer summary: call zap_scan_history_customer_handoff after reviewing release-evidence warnings.")
                .toString();
    }

    private String normalizeDefinitionType(String definitionType) {
        String normalized = requireText(definitionType, "definitionType").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openapi", "graphql", "soap" -> normalized;
            default -> throw new IllegalArgumentException("definitionType must be one of: openapi, graphql, soap");
        };
    }

    private String normalizeSourceKind(String sourceKind) {
        String normalized = requireText(sourceKind, "sourceKind").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "url", "file" -> normalized;
            default -> throw new IllegalArgumentException("sourceKind must be one of: url, file");
        };
    }

    private String normalizeReportFormat(String format) {
        if (!hasText(format)) {
            return REPORT_FORMAT_HTML;
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case REPORT_FORMAT_HTML, REPORT_FORMAT_JSON -> normalized;
            default -> throw new IllegalArgumentException("format must be one of: html, json");
        };
    }

    private String reportTemplateFor(String reportFormat) {
        return REPORT_FORMAT_JSON.equals(reportFormat) ? "traditional-json-plus" : "traditional-html-plus";
    }

    private String trimToEmpty(String value) {
        return hasText(value) ? value.trim() : "";
    }

    private String targetScope(TargetDescriptor target) {
        if (target == null || !hasText(target.baseUrl())) {
            return "All targets";
        }
        return target.baseUrl();
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
