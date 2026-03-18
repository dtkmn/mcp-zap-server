package mcp.server.zap.core.service;

import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Guided scan orchestration for crawl and attack flows without exposing MCP transport details.
 */
@Service
public class GuidedScanWorkflowService {
    private static final String PREFIX_OPERATION_ID = "zop_";
    private static final String STRATEGY_ACTIVE = "active";
    private static final String STRATEGY_AUTO = "auto";
    private static final String STRATEGY_BROWSER = "browser";
    private static final String STRATEGY_HTTP = "http";
    private static final String JOB_ID_PREFIX = "Job ID: ";
    private static final String SCAN_ID_PREFIX = "Scan ID: ";
    private static final String AUTO_BROWSER_FALLBACK_NOTE =
            "Auto strategy fell back from the HTTP spider to the browser-backed crawler because the direct spider failed.";
    private static final String AUTO_QUEUE_HTTP_NOTE =
            "Auto strategy in queued mode currently selects the HTTP spider by default. Pass strategy=browser to force the browser-backed crawler.";

    private final GuidedExecutionModeResolver executionModeResolver;
    private final SpiderScanService spiderScanService;
    private final AjaxSpiderService ajaxSpiderService;
    private final ActiveScanService activeScanService;
    private final ScanJobQueueService scanJobQueueService;

    public GuidedScanWorkflowService(GuidedExecutionModeResolver executionModeResolver,
                                     SpiderScanService spiderScanService,
                                     AjaxSpiderService ajaxSpiderService,
                                     ActiveScanService activeScanService,
                                     ScanJobQueueService scanJobQueueService) {
        this.executionModeResolver = executionModeResolver;
        this.spiderScanService = spiderScanService;
        this.ajaxSpiderService = ajaxSpiderService;
        this.activeScanService = activeScanService;
        this.scanJobQueueService = scanJobQueueService;
    }

    public String startCrawl(String targetUrl, String strategy, String idempotencyKey) {
        String normalizedTargetUrl = requireText(targetUrl, "targetUrl");
        return formatStartedOperation(
                normalizedTargetUrl,
                startCrawlOperation(normalizedTargetUrl, normalizeCrawlStrategy(strategy), trimToNull(idempotencyKey))
        );
    }

    public String getCrawlStatus(String operationId) {
        return formatStatusMessage(operationId, OperationKind.CRAWL);
    }

    public String stopCrawl(String operationId) {
        return formatStopMessage(operationId, OperationKind.CRAWL);
    }

    public String startAttack(String targetUrl, String recurse, String policy, String idempotencyKey) {
        String normalizedTargetUrl = requireText(targetUrl, "targetUrl");
        return formatStartedOperation(
                normalizedTargetUrl,
                startAttackOperation(normalizedTargetUrl, recurse, policy, trimToNull(idempotencyKey))
        );
    }

    public String getAttackStatus(String operationId) {
        return formatStatusMessage(operationId, OperationKind.ATTACK);
    }

    public String stopAttack(String operationId) {
        return formatStopMessage(operationId, OperationKind.ATTACK);
    }

    private StartedOperation startCrawlOperation(String targetUrl, String requestedStrategy, String idempotencyKey) {
        GuidedExecutionModeResolver.ExecutionMode executionMode = executionModeResolver.resolveDefaultMode();
        if (executionMode == GuidedExecutionModeResolver.ExecutionMode.QUEUE) {
            String effectiveStrategy = STRATEGY_BROWSER.equals(requestedStrategy) ? STRATEGY_BROWSER : STRATEGY_HTTP;
            String delegateResponse = STRATEGY_BROWSER.equals(effectiveStrategy)
                    ? scanJobQueueService.queueAjaxSpiderScan(targetUrl, idempotencyKey)
                    : scanJobQueueService.queueSpiderScan(targetUrl, idempotencyKey);
            String note = STRATEGY_AUTO.equals(requestedStrategy) ? AUTO_QUEUE_HTTP_NOTE : null;
            return queuedOperation(OperationKind.CRAWL, effectiveStrategy, delegateResponse, note);
        }

        if (STRATEGY_BROWSER.equals(requestedStrategy)) {
            return directBrowserCrawl(targetUrl, null);
        }

        try {
            return directHttpCrawl(targetUrl);
        } catch (ZapApiException e) {
            if (!STRATEGY_AUTO.equals(requestedStrategy)) {
                throw e;
            }
            return directBrowserCrawl(targetUrl, AUTO_BROWSER_FALLBACK_NOTE);
        }
    }

    private StartedOperation startAttackOperation(String targetUrl, String recurse, String policy, String idempotencyKey) {
        GuidedExecutionModeResolver.ExecutionMode executionMode = executionModeResolver.resolveDefaultMode();
        if (executionMode == GuidedExecutionModeResolver.ExecutionMode.QUEUE) {
            return queuedOperation(
                    OperationKind.ATTACK,
                    STRATEGY_ACTIVE,
                    scanJobQueueService.queueActiveScan(targetUrl, recurse, policy, idempotencyKey),
                    null
            );
        }

        String delegateResponse = activeScanService.startActiveScan(targetUrl, recurse, policy);
        return startedOperation(
                OperationKind.ATTACK,
                executionMode,
                STRATEGY_ACTIVE,
                extractValueByPrefix(delegateResponse, SCAN_ID_PREFIX),
                delegateResponse,
                null
        );
    }

    private StartedOperation directHttpCrawl(String targetUrl) {
        String delegateResponse = spiderScanService.startSpiderScan(targetUrl);
        return startedOperation(
                OperationKind.CRAWL,
                GuidedExecutionModeResolver.ExecutionMode.DIRECT,
                STRATEGY_HTTP,
                extractValueByPrefix(delegateResponse, SCAN_ID_PREFIX),
                delegateResponse,
                null
        );
    }

    private StartedOperation directBrowserCrawl(String targetUrl, String note) {
        String delegateResponse = ajaxSpiderService.startAjaxSpider(targetUrl);
        return startedOperation(
                OperationKind.CRAWL,
                GuidedExecutionModeResolver.ExecutionMode.DIRECT,
                STRATEGY_BROWSER,
                UUID.randomUUID().toString(),
                delegateResponse,
                note
        );
    }

    private StartedOperation queuedOperation(OperationKind kind, String strategy, String delegateResponse, String note) {
        return startedOperation(
                kind,
                GuidedExecutionModeResolver.ExecutionMode.QUEUE,
                strategy,
                extractValueByPrefix(delegateResponse, JOB_ID_PREFIX),
                delegateResponse,
                note
        );
    }

    private StartedOperation startedOperation(OperationKind kind,
                                              GuidedExecutionModeResolver.ExecutionMode executionMode,
                                              String strategy,
                                              String backendId,
                                              String delegateResponse,
                                              String note) {
        return new StartedOperation(new GuidedOperation(kind, executionMode, strategy, backendId), delegateResponse, note);
    }

    private String getOperationStatus(GuidedOperation operation) {
        return switch (operation.kind()) {
            case CRAWL -> switch (operation.executionMode()) {
                case QUEUE -> scanJobQueueService.getScanJobStatus(operation.backendId());
                case DIRECT -> STRATEGY_BROWSER.equals(operation.strategy())
                        ? ajaxSpiderService.getAjaxSpiderStatus()
                        : spiderScanService.getSpiderScanStatus(operation.backendId());
            };
            case ATTACK -> operation.executionMode() == GuidedExecutionModeResolver.ExecutionMode.QUEUE
                    ? scanJobQueueService.getScanJobStatus(operation.backendId())
                    : activeScanService.getActiveScanStatus(operation.backendId());
        };
    }

    private String stopOperation(GuidedOperation operation) {
        return switch (operation.kind()) {
            case CRAWL -> switch (operation.executionMode()) {
                case QUEUE -> scanJobQueueService.cancelScanJob(operation.backendId());
                case DIRECT -> STRATEGY_BROWSER.equals(operation.strategy())
                        ? ajaxSpiderService.stopAjaxSpider()
                        : spiderScanService.stopSpiderScan(operation.backendId());
            };
            case ATTACK -> operation.executionMode() == GuidedExecutionModeResolver.ExecutionMode.QUEUE
                    ? scanJobQueueService.cancelScanJob(operation.backendId())
                    : activeScanService.stopActiveScan(operation.backendId());
        };
    }

    private String formatStartedOperation(String targetUrl, StartedOperation started) {
        String operationId = encodeOperation(started.operation());
        StringBuilder output = new StringBuilder();
        output.append("Guided ").append(started.operation().kind().wireValue()).append(" started.\n")
                .append("Operation ID: ").append(operationId).append('\n')
                .append("Execution Mode: ").append(formatExecutionMode(started.operation().executionMode())).append('\n')
                .append("Strategy: ").append(started.operation().strategy()).append('\n')
                .append("Target URL: ").append(targetUrl).append('\n');
        if (hasText(started.note())) {
            output.append("Note: ").append(started.note().trim()).append('\n');
        }
        output.append("Use 'zap_").append(started.operation().kind().wireValue()).append("_status' with this operation ID.\n")
                .append('\n')
                .append(started.delegateResponse());
        return output.toString();
    }

    private String formatStatusMessage(String operationId, OperationKind expectedKind) {
        GuidedOperation operation = decodeOperation(operationId, expectedKind);
        return formatLifecycleMessage("status", operationId, operation, getOperationStatus(operation));
    }

    private String formatStopMessage(String operationId, OperationKind expectedKind) {
        GuidedOperation operation = decodeOperation(operationId, expectedKind);
        return formatLifecycleMessage("stop requested", operationId, operation, stopOperation(operation));
    }

    private String formatLifecycleMessage(String action,
                                          String operationId,
                                          GuidedOperation operation,
                                          String delegateResponse) {
        return new StringBuilder()
                .append("Guided ").append(operation.kind().wireValue()).append(' ').append(action).append(".\n")
                .append("Operation ID: ").append(operationId).append('\n')
                .append("Execution Mode: ").append(formatExecutionMode(operation.executionMode())).append('\n')
                .append("Strategy: ").append(operation.strategy()).append('\n')
                .append('\n')
                .append(delegateResponse)
                .toString();
    }

    private String normalizeCrawlStrategy(String strategy) {
        if (!hasText(strategy)) {
            return STRATEGY_AUTO;
        }
        String normalized = strategy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case STRATEGY_AUTO, STRATEGY_HTTP, STRATEGY_BROWSER -> normalized;
            default -> throw new IllegalArgumentException("strategy must be one of: auto, http, browser");
        };
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String formatExecutionMode(GuidedExecutionModeResolver.ExecutionMode executionMode) {
        return executionMode.name().toLowerCase(Locale.ROOT);
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

    private String extractValueByPrefix(String response, String prefix) {
        if (!hasText(response)) {
            throw new IllegalStateException("Tool response was blank while trying to extract " + prefix);
        }
        for (String line : response.split("\\R")) {
            if (line.startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
                break;
            }
        }
        throw new IllegalStateException("Unable to extract value with prefix '" + prefix + "' from response: " + response);
    }

    private String encodeOperation(GuidedOperation operation) {
        String payload = String.join("|",
                "v1",
                operation.kind().wireValue(),
                formatExecutionMode(operation.executionMode()),
                operation.strategy(),
                operation.backendId() == null ? "" : operation.backendId());
        return PREFIX_OPERATION_ID + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private GuidedOperation decodeOperation(String operationId, OperationKind expectedKind) {
        String normalizedOperationId = requireText(operationId, "operationId");
        if (!normalizedOperationId.startsWith(PREFIX_OPERATION_ID)) {
            throw new IllegalArgumentException("operationId is not a guided operation ID");
        }
        String payload;
        try {
            payload = new String(
                    Base64.getUrlDecoder().decode(normalizedOperationId.substring(PREFIX_OPERATION_ID.length())),
                    StandardCharsets.UTF_8
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("operationId is malformed", e);
        }

        String[] parts = payload.split("\\|", -1);
        if (parts.length != 5 || !"v1".equals(parts[0])) {
            throw new IllegalArgumentException("operationId has an unsupported format");
        }

        GuidedOperation operation = new GuidedOperation(
                OperationKind.fromWireValue(parts[1]),
                parseExecutionMode(parts[2]),
                parts[3],
                parts[4]
        );
        if (operation.kind() != expectedKind) {
            throw new IllegalArgumentException("operationId belongs to a different guided flow");
        }
        return operation;
    }

    private GuidedExecutionModeResolver.ExecutionMode parseExecutionMode(String executionMode) {
        try {
            return GuidedExecutionModeResolver.ExecutionMode.valueOf(
                    requireText(executionMode, "executionMode").toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("operationId has an unsupported execution mode", e);
        }
    }

    private record GuidedOperation(
            OperationKind kind,
            GuidedExecutionModeResolver.ExecutionMode executionMode,
            String strategy,
            String backendId
    ) {
    }

    private record StartedOperation(GuidedOperation operation, String delegateResponse, String note) {
    }

    private enum OperationKind {
        CRAWL("crawl"),
        ATTACK("attack");

        private final String wireValue;

        OperationKind(String wireValue) {
            this.wireValue = wireValue;
        }

        private String wireValue() {
            return wireValue;
        }

        private static OperationKind fromWireValue(String wireValue) {
            String normalized = wireValue == null ? "" : wireValue.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "crawl" -> CRAWL;
                case "attack" -> ATTACK;
                default -> throw new IllegalArgumentException("operationId belongs to an unsupported guided flow");
            };
        }
    }
}
