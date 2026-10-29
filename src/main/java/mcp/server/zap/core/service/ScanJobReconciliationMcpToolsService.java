package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.ScanLaunchSafetyProperties;
import mcp.server.zap.core.configuration.ToolAuthorizationProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import mcp.server.zap.core.service.queue.ScanJobLaunchSafety;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Trusted operator recovery; ordinary scan scopes and wildcard grants cannot release uncertain work. */
@Service
@Slf4j
public class ScanJobReconciliationMcpToolsService {
    public static final String SCOPE = "zap:scan:queue:reconcile";
    private final ScanJobQueueService queue;
    private final ScanJobLaunchSafety safety;
    private final ScanLaunchSafetyProperties properties;
    private final ToolAuthorizationProperties authorization;
    private final Environment environment;

    public ScanJobReconciliationMcpToolsService(ScanJobQueueService queue, ScanJobLaunchSafety safety,
                                               ScanLaunchSafetyProperties properties,
                                               ToolAuthorizationProperties authorization, Environment environment) {
        this.queue = queue;
        this.safety = safety;
        this.properties = properties;
        this.authorization = authorization;
        this.environment = environment;
    }

    @Tool(name = "zap_scan_job_reconcile", description = "Operator-only reconciliation of a held native scan launch. "
            + "Requires enforced API-key/JWT authentication, exact reconciliation scope and an approved operator client. "
            + "The confirmation is a trusted operator assertion, not native engine-generation proof. "
            + "attach_scan attests this exact job/attempt owns the native scan on the current dedicated engine; AJAX attach is unsupported. "
            + "confirm_stopped attests its work and originating requests cannot still execute, including after engine replacement. "
            + "Neither action launches work or declares a successful security result.")
    public String reconcile(
            @ToolParam(description = "Existing queue job ID visible in the operator's workspace") String jobId,
            @ToolParam(description = "Exact launch attempt ID from current job status") String launchAttemptId,
            @ToolParam(description = "Exact reconciliation version from current job status") long expectedResolutionVersion,
            @ToolParam(description = "attach_scan or confirm_stopped") String resolution,
            @ToolParam(required = false, description = "Native scan ID whose ownership the operator attests; required for attach_scan") String nativeScanId,
            @ToolParam(description = "Explicit trusted assertion of exact ownership or confirmed cessation, including old controller requests") boolean confirmOwnership) {
        requireAuthority();
        queue.requireReconciliationVisibleJob(jobId);
        ScanJob result = safety.reconcile(jobId, launchAttemptId, expectedResolutionVersion,
                resolution, nativeScanId, confirmOwnership);
        log.info("Scan launch reconciled operator={} workspace={} job={} attempt={} version={} resolution={} outcome={}",
                RequestIdentityHolder.currentClientId(), queue.reconciliationWorkspaceId(), result.getId(),
                launchAttemptId, expectedResolutionVersion, resolution, result.getStatus());
        return "Scan launch reconciled by trusted operator assertion.\nJob ID: " + result.getId()
                + "\nStatus: " + result.getStatus() + "\nLaunch Attempt: " + result.getLaunchAttempt().id()
                + "\nReconciliation Version: " + result.getLaunchAttempt().resolutionVersion()
                + "\nNo new scan was launched and no scan success was asserted.";
    }

    @Tool(name = "zap_scan_job_reconciliation_status", description = "Operator-only recovery discovery of visible held launch receipts, "
            + "or one visible job. Uses the same exact reconciliation authority. Does not dispatch, poll or stop native work.")
    public String status(@ToolParam(required = false, description = "Optional existing queue job ID; omit to list up to 100 visible held jobs") String jobId) {
        requireAuthority();
        return queue.getReconciliationStatus(jobId);
    }

    private void requireAuthority() {
        String mode = environment.getProperty("mcp.server.security.mode", "api-key");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String client = RequestIdentityHolder.currentClientId();
        boolean exactScope = authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> ("SCOPE_" + SCOPE).equals(a.getAuthority()));
        if (!environment.getProperty("mcp.server.security.enabled", Boolean.class, true)
                || !(mode.equalsIgnoreCase("api-key") || mode.equalsIgnoreCase("jwt"))
                || authorization.getMode() != ToolAuthorizationProperties.Mode.ENFORCE
                || !exactScope || client == null || !client.equals(authentication.getName())
                || !properties.getOperatorClientIds().contains(client)) {
            throw new AccessDeniedException("Launch reconciliation requires enforced authentication, an exact scope and an approved operator identity; wildcard grants do not authorize it");
        }
    }
}
