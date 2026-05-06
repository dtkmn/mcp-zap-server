package mcp.server.zap.core.service;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineAdapter;
import mcp.server.zap.core.gateway.EngineCapability;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.ScanRunRecord;
import mcp.server.zap.core.gateway.TargetDescriptor;
import mcp.server.zap.core.service.auth.bootstrap.AuthBootstrapKind;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import mcp.server.zap.core.service.auth.bootstrap.PreparedAuthSession;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Guided scan orchestration for crawl and attack flows without exposing MCP transport details.
 */
@Service
public class GuidedScanWorkflowService {
    private static final String PREFIX_OPERATION_ID = "zop_";
    private static final byte[] PROCESS_OPERATION_SIGNING_SECRET =
            UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
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
    private final GuidedAuthSessionService guidedAuthSessionService;
    private final EngineAdapter engineAdapter;
    private final GatewayRecordFactory gatewayRecordFactory;
    private ClientWorkspaceResolver clientWorkspaceResolver;

    @Value("${mcp.server.guided.operation-signing-secret:${JWT_SECRET:}}")
    private String operationSigningSecret;

    public GuidedScanWorkflowService(GuidedExecutionModeResolver executionModeResolver,
                                     SpiderScanService spiderScanService,
                                     AjaxSpiderService ajaxSpiderService,
                                     ActiveScanService activeScanService,
                                     ScanJobQueueService scanJobQueueService,
                                     GuidedAuthSessionService guidedAuthSessionService,
                                     EngineAdapter engineAdapter,
                                     GatewayRecordFactory gatewayRecordFactory) {
        this.executionModeResolver = executionModeResolver;
        this.spiderScanService = spiderScanService;
        this.ajaxSpiderService = ajaxSpiderService;
        this.activeScanService = activeScanService;
        this.scanJobQueueService = scanJobQueueService;
        this.guidedAuthSessionService = guidedAuthSessionService;
        this.engineAdapter = engineAdapter;
        this.gatewayRecordFactory = gatewayRecordFactory;
    }

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    public String startCrawl(String targetUrl, String strategy, String idempotencyKey, String authSessionId) {
        String normalizedTargetUrl = requireText(targetUrl, "targetUrl");
        gatewayRecordFactory.requireCapability(engineAdapter, EngineCapability.GUIDED_CRAWL, "guided crawl");
        TargetDescriptor target = gatewayRecordFactory.targetFromUrl(normalizedTargetUrl, TargetDescriptor.Kind.WEB);
        PreparedAuthSession preparedAuthSession = resolvePreparedFormSession(authSessionId, normalizedTargetUrl);
        return formatStartedOperation(
                normalizedTargetUrl,
                startCrawlOperation(
                        normalizedTargetUrl,
                        target,
                        normalizeCrawlStrategy(strategy),
                        trimToNull(idempotencyKey),
                        preparedAuthSession
                )
        );
    }

    public String getCrawlStatus(String operationId) {
        return formatStatusMessage(operationId, OperationKind.CRAWL);
    }

    public String stopCrawl(String operationId) {
        return formatStopMessage(operationId, OperationKind.CRAWL);
    }

    public String startAttack(String targetUrl, String recurse, String policy, String idempotencyKey, String authSessionId) {
        String normalizedTargetUrl = requireText(targetUrl, "targetUrl");
        gatewayRecordFactory.requireCapability(engineAdapter, EngineCapability.GUIDED_ATTACK, "guided attack");
        TargetDescriptor target = gatewayRecordFactory.targetFromUrl(normalizedTargetUrl, TargetDescriptor.Kind.WEB);
        PreparedAuthSession preparedAuthSession = resolvePreparedFormSession(authSessionId, normalizedTargetUrl);
        return formatStartedOperation(
                normalizedTargetUrl,
                startAttackOperation(normalizedTargetUrl, target, recurse, policy, trimToNull(idempotencyKey), preparedAuthSession)
        );
    }

    public String getAttackStatus(String operationId) {
        return formatStatusMessage(operationId, OperationKind.ATTACK);
    }

    public String stopAttack(String operationId) {
        return formatStopMessage(operationId, OperationKind.ATTACK);
    }

    private StartedOperation startCrawlOperation(String targetUrl,
                                                 TargetDescriptor target,
                                                 String requestedStrategy,
                                                 String idempotencyKey,
                                                 PreparedAuthSession preparedAuthSession) {
        if (preparedAuthSession != null && STRATEGY_BROWSER.equals(requestedStrategy)) {
            throw new IllegalArgumentException(
                    "Authenticated guided crawl currently supports the HTTP spider only. strategy=browser is not supported with authSessionId."
            );
        }

        GuidedExecutionModeResolver.ExecutionMode executionMode = executionModeResolver.resolveDefaultMode();
        if (executionMode == GuidedExecutionModeResolver.ExecutionMode.QUEUE) {
            String effectiveStrategy = STRATEGY_BROWSER.equals(requestedStrategy) ? STRATEGY_BROWSER : STRATEGY_HTTP;
            String delegateResponse;
            if (preparedAuthSession != null) {
                delegateResponse = scanJobQueueService.queueSpiderScanAsUser(
                        preparedAuthSession.contextId(),
                        preparedAuthSession.userId(),
                        targetUrl,
                        null,
                        "true",
                        "false",
                        idempotencyKey
                );
                effectiveStrategy = STRATEGY_HTTP;
            } else {
                delegateResponse = STRATEGY_BROWSER.equals(effectiveStrategy)
                    ? scanJobQueueService.queueAjaxSpiderScan(targetUrl, idempotencyKey)
                    : scanJobQueueService.queueSpiderScan(targetUrl, idempotencyKey);
            }
            String note = preparedAuthSession != null
                    ? "Authenticated guided crawl applied the prepared form-login session via ZAP context/user routing."
                    : (STRATEGY_AUTO.equals(requestedStrategy) ? AUTO_QUEUE_HTTP_NOTE : null);
            return startedOperation(
                    OperationKind.CRAWL,
                    GuidedExecutionModeResolver.ExecutionMode.QUEUE,
                    effectiveStrategy,
                    extractValueByPrefix(delegateResponse, JOB_ID_PREFIX),
                    target,
                    delegateResponse,
                    note,
                    preparedAuthSession
            );
        }

        if (preparedAuthSession != null) {
            return directAuthenticatedHttpCrawl(targetUrl, preparedAuthSession);
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

    private StartedOperation startAttackOperation(String targetUrl,
                                                  TargetDescriptor target,
                                                  String recurse,
                                                  String policy,
                                                  String idempotencyKey,
                                                  PreparedAuthSession preparedAuthSession) {
        GuidedExecutionModeResolver.ExecutionMode executionMode = executionModeResolver.resolveDefaultMode();
        if (executionMode == GuidedExecutionModeResolver.ExecutionMode.QUEUE) {
            String delegateResponse = preparedAuthSession != null
                    ? scanJobQueueService.queueActiveScanAsUser(
                            preparedAuthSession.contextId(),
                            preparedAuthSession.userId(),
                            targetUrl,
                            recurse,
                            policy,
                            idempotencyKey
                    )
                    : scanJobQueueService.queueActiveScan(targetUrl, recurse, policy, idempotencyKey);
            return startedOperation(
                    OperationKind.ATTACK,
                    GuidedExecutionModeResolver.ExecutionMode.QUEUE,
                    STRATEGY_ACTIVE,
                    extractValueByPrefix(delegateResponse, JOB_ID_PREFIX),
                    target,
                    delegateResponse,
                    preparedAuthSession != null
                            ? "Authenticated guided attack applied the prepared form-login session via ZAP context/user routing."
                            : null,
                    preparedAuthSession
            );
        }

        String delegateResponse = preparedAuthSession != null
                ? activeScanService.startActiveScanAsUser(
                        preparedAuthSession.contextId(),
                        preparedAuthSession.userId(),
                        targetUrl,
                        recurse,
                        policy
                )
                : activeScanService.startActiveScan(targetUrl, recurse, policy);
        return startedOperation(
                OperationKind.ATTACK,
                executionMode,
                STRATEGY_ACTIVE,
                extractValueByPrefix(delegateResponse, SCAN_ID_PREFIX),
                target,
                delegateResponse,
                preparedAuthSession != null
                        ? "Authenticated guided attack applied the prepared form-login session via ZAP context/user routing."
                        : null,
                preparedAuthSession
        );
    }

    private StartedOperation directHttpCrawl(String targetUrl) {
        String delegateResponse = spiderScanService.startSpiderScan(targetUrl);
        return startedOperation(
                OperationKind.CRAWL,
                GuidedExecutionModeResolver.ExecutionMode.DIRECT,
                STRATEGY_HTTP,
                extractValueByPrefix(delegateResponse, SCAN_ID_PREFIX),
                gatewayRecordFactory.targetFromUrl(targetUrl, TargetDescriptor.Kind.WEB),
                delegateResponse,
                null,
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
                gatewayRecordFactory.targetFromUrl(targetUrl, TargetDescriptor.Kind.WEB),
                delegateResponse,
                note,
                null
        );
    }

    private StartedOperation directAuthenticatedHttpCrawl(String targetUrl, PreparedAuthSession preparedAuthSession) {
        String delegateResponse = spiderScanService.startSpiderScanAsUser(
                preparedAuthSession.contextId(),
                preparedAuthSession.userId(),
                targetUrl,
                null,
                "true",
                "false"
        );
        return startedOperation(
                OperationKind.CRAWL,
                GuidedExecutionModeResolver.ExecutionMode.DIRECT,
                STRATEGY_HTTP,
                extractValueByPrefix(delegateResponse, SCAN_ID_PREFIX),
                gatewayRecordFactory.targetFromUrl(targetUrl, TargetDescriptor.Kind.WEB),
                delegateResponse,
                "Authenticated guided crawl applied the prepared form-login session via ZAP context/user routing.",
                preparedAuthSession
        );
    }

    private StartedOperation queuedOperation(OperationKind kind, String strategy, String delegateResponse, String note) {
        return startedOperation(
                kind,
                GuidedExecutionModeResolver.ExecutionMode.QUEUE,
                strategy,
                extractValueByPrefix(delegateResponse, JOB_ID_PREFIX),
                new TargetDescriptor(TargetDescriptor.Kind.WEB, null, "All targets"),
                delegateResponse,
                note,
                null
        );
    }

    private StartedOperation startedOperation(OperationKind kind,
                                              GuidedExecutionModeResolver.ExecutionMode executionMode,
                                              String strategy,
                                              String backendId,
                                              TargetDescriptor target,
                                              String delegateResponse,
                                              String note,
                                              PreparedAuthSession preparedAuthSession) {
        ScanRunRecord scanRun = gatewayRecordFactory.scanRun(
                engineAdapter,
                backendId,
                kind.wireValue(),
                "started",
                target,
                formatExecutionMode(executionMode),
                backendId
        );
        return new StartedOperation(
                new GuidedOperation(kind, executionMode, strategy, backendId),
                scanRun,
                delegateResponse,
                note,
                preparedAuthSession
        );
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
        if (started.preparedAuthSession() != null) {
            output.append("Authenticated Session: ").append(started.preparedAuthSession().sessionId()).append('\n')
                    .append("Context ID: ").append(started.preparedAuthSession().contextId()).append('\n')
                    .append("User ID: ").append(started.preparedAuthSession().userId()).append('\n');
        }
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
                "v2",
                encodePart(operation.kind().wireValue()),
                encodePart(formatExecutionMode(operation.executionMode())),
                encodePart(operation.strategy()),
                encodePart(operation.backendId()),
                encodePart(currentClientId()),
                encodePart(currentWorkspaceId()),
                encodePart(UUID.randomUUID().toString()));
        String signedPayload = payload + "|" + signPayload(payload);
        return PREFIX_OPERATION_ID + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(signedPayload.getBytes(StandardCharsets.UTF_8));
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
        if (parts.length != 9 || !"v2".equals(parts[0])) {
            throw new IllegalArgumentException("operationId has an unsupported format");
        }
        String signedPayload = String.join("|",
                parts[0],
                parts[1],
                parts[2],
                parts[3],
                parts[4],
                parts[5],
                parts[6],
                parts[7]
        );
        if (!MessageDigest.isEqual(
                signPayload(signedPayload).getBytes(StandardCharsets.UTF_8),
                parts[8].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("operationId signature is invalid");
        }

        String operationClientId = decodePart(parts[5], "clientId");
        String operationWorkspaceId = decodePart(parts[6], "workspaceId");
        if (!operationClientId.equals(currentClientId()) || !operationWorkspaceId.equals(currentWorkspaceId())) {
            throw new IllegalArgumentException("operationId is not visible to the current requester");
        }

        GuidedOperation operation = new GuidedOperation(
                OperationKind.fromWireValue(decodePart(parts[1], "operationKind")),
                parseExecutionMode(decodePart(parts[2], "executionMode")),
                decodePart(parts[3], "strategy"),
                decodePart(parts[4], "backendId")
        );
        if (operation.kind() != expectedKind) {
            throw new IllegalArgumentException("operationId belongs to a different guided flow");
        }
        return operation;
    }

    private String encodePart(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String decodePart(String value, String fieldName) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("operationId has malformed " + fieldName, e);
        }
    }

    private String signPayload(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(operationSigningSecretBytes(), "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign guided operation ID", e);
        }
    }

    private byte[] operationSigningSecretBytes() {
        if (hasText(operationSigningSecret)) {
            return operationSigningSecret.trim().getBytes(StandardCharsets.UTF_8);
        }
        return PROCESS_OPERATION_SIGNING_SECRET;
    }

    private String currentClientId() {
        if (clientWorkspaceResolver == null) {
            return "anonymous";
        }
        String clientId = clientWorkspaceResolver.resolveCurrentClientId();
        return hasText(clientId) ? clientId.trim() : "anonymous";
    }

    private String currentWorkspaceId() {
        if (clientWorkspaceResolver == null) {
            return "default-workspace";
        }
        String workspaceId = clientWorkspaceResolver.resolveCurrentWorkspaceId();
        return hasText(workspaceId) ? workspaceId.trim() : "default-workspace";
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

    private PreparedAuthSession resolvePreparedFormSession(String authSessionId, String targetUrl) {
        if (!hasText(authSessionId)) {
            return null;
        }
        PreparedAuthSession session = guidedAuthSessionService.getPreparedSession(authSessionId);
        if (session.authKind() != AuthBootstrapKind.FORM) {
            throw new IllegalArgumentException(
                    "Authenticated guided scan execution currently supports form-login sessions only. Re-prepare a form session or omit authSessionId."
            );
        }
        if (!session.engineBound() || !hasText(session.contextId()) || !hasText(session.userId())) {
            throw new IllegalArgumentException(
                    "Prepared auth session is not bound to a reusable ZAP context/user. Re-run zap_auth_session_prepare for a form-login flow."
            );
        }
        if (!sameAuthority(targetUrl, session.target().baseUrl())) {
            throw new IllegalArgumentException(
                    "authSessionId target host does not match the requested targetUrl. Reuse the session only on the same app host."
            );
        }
        return session;
    }

    private boolean sameAuthority(String left, String right) {
        URI leftUri = URI.create(requireText(left, "targetUrl"));
        URI rightUri = URI.create(requireText(right, "sessionTargetUrl"));
        String leftScheme = leftUri.getScheme() == null ? "" : leftUri.getScheme().trim().toLowerCase(Locale.ROOT);
        String rightScheme = rightUri.getScheme() == null ? "" : rightUri.getScheme().trim().toLowerCase(Locale.ROOT);
        String leftAuthority = leftUri.getAuthority() == null ? "" : leftUri.getAuthority().trim().toLowerCase(Locale.ROOT);
        String rightAuthority = rightUri.getAuthority() == null ? "" : rightUri.getAuthority().trim().toLowerCase(Locale.ROOT);
        return leftScheme.equals(rightScheme) && leftAuthority.equals(rightAuthority);
    }

    private record GuidedOperation(
            OperationKind kind,
            GuidedExecutionModeResolver.ExecutionMode executionMode,
            String strategy,
            String backendId
    ) {
    }

    private record StartedOperation(
            GuidedOperation operation,
            ScanRunRecord scanRun,
            String delegateResponse,
            String note,
            PreparedAuthSession preparedAuthSession
    ) {
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
