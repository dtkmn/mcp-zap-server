package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.configuration.ScanLimitProperties;
import mcp.server.zap.exception.ZapApiException;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * Internal service for managing ZAP active scans.
 * Queue orchestration calls these methods to start, monitor, and stop scans.
 */
@Slf4j
@Service
public class ActiveScanService {

    private final ClientApi zap;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;

    public ActiveScanService(ClientApi zap, 
                            UrlValidationService urlValidationService,
                            ScanLimitProperties scanLimitProperties) {
        this.zap = zap;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
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

    String startActiveScanJob(String targetUrl, String recurse, String policy) {
        urlValidationService.validateUrl(targetUrl);

        try {
            zap.ascan.enableAllScanners(null);
            zap.ascan.setOptionMaxScanDurationInMins(scanLimitProperties.getMaxActiveScanDurationInMins());
            zap.ascan.setOptionHostPerScan(scanLimitProperties.getHostPerScan());
            zap.ascan.setOptionThreadPerHost(scanLimitProperties.getThreadPerHost());

            ApiResponseElement scanResp = (ApiResponseElement) zap.ascan.scan(
                    targetUrl,
                    recurse,
                    "false",
                    policy,
                    null,
                    null
            );

            if (scanResp == null) {
                throw new IllegalStateException("Failed to start scan on " + targetUrl + ": received null response");
            }

            String scanId = scanResp.getValue();
            log.info("Started active scan with ID {} on {} with policy: {}, maxDuration: {} mins",
                    scanId, targetUrl, policy, scanLimitProperties.getMaxActiveScanDurationInMins());
            return scanId;
        } catch (ClientApiException e) {
            log.error("Error starting active scan on {}: {}", targetUrl, e.getMessage(), e);
            throw new ZapApiException("Error starting active scan on " + targetUrl + ": " + e.getMessage(), e);
        }
    }

    String startActiveScanAsUserJob(String contextId,
                                    String userId,
                                    String targetUrl,
                                    String recurse,
                                    String policy) {
        String normalizedContextId = requireText(contextId, "contextId");
        String normalizedUserId = requireText(userId, "userId");
        urlValidationService.validateUrl(targetUrl);

        String effectiveRecurse = hasText(recurse) ? recurse.trim() : "true";
        String effectivePolicy = hasText(policy) ? policy.trim() : null;

        try {
            zap.ascan.enableAllScanners(null);
            zap.ascan.setOptionMaxScanDurationInMins(scanLimitProperties.getMaxActiveScanDurationInMins());
            zap.ascan.setOptionHostPerScan(scanLimitProperties.getHostPerScan());
            zap.ascan.setOptionThreadPerHost(scanLimitProperties.getThreadPerHost());

            ApiResponseElement scanResp = (ApiResponseElement) zap.ascan.scanAsUser(
                    targetUrl,
                    normalizedContextId,
                    normalizedUserId,
                    effectiveRecurse,
                    effectivePolicy,
                    null,
                    null
            );

            if (scanResp == null) {
                throw new IllegalStateException("Failed to start scan-as-user on " + targetUrl + ": received null response");
            }

            String scanId = scanResp.getValue();
            log.info("Started active scan-as-user with ID {} on {} for context {}, user {}",
                    scanId, targetUrl, normalizedContextId, normalizedUserId);
            return scanId;
        } catch (ClientApiException e) {
            log.error("Error starting active scan-as-user on {}: {}", targetUrl, e.getMessage(), e);
            throw new ZapApiException("Error starting active scan-as-user on " + targetUrl + ": " + e.getMessage(), e);
        }
    }

    int getActiveScanProgressPercent(String scanId) {
        try {
            ApiResponse resp = zap.ascan.status(scanId);
            if (!(resp instanceof ApiResponseElement element)) {
                throw new IllegalStateException("Unexpected response from ascan.status(): " + resp);
            }
            return Integer.parseInt(element.getValue());
        } catch (ClientApiException e) {
            log.error("Error retrieving active scan status for {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error retrieving status for active scan " + scanId, e);
        }
    }

    void stopActiveScanJob(String scanId) {
        try {
            zap.ascan.stop(scanId);
        } catch (ClientApiException e) {
            log.error("Error stopping active scan {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error stopping active scan " + scanId, e);
        }
    }

}
