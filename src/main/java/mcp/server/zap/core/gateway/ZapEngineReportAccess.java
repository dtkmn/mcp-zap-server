package mcp.server.zap.core.gateway;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * ZAP-backed implementation of the gateway report access boundary.
 */
@Slf4j
@Component
public class ZapEngineReportAccess implements EngineReportAccess {

    private final ClientApi zap;

    public ZapEngineReportAccess(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public List<String> listReportTemplates() {
        try {
            ApiResponse raw = zap.reports.templates();
            if (!(raw instanceof ApiResponseList list)) {
                throw new IllegalStateException("Getting report templates failed: " + raw);
            }
            List<String> templates = new ArrayList<>();
            for (ApiResponse item : list.getItems()) {
                templates.add(item.toString());
            }
            return List.copyOf(templates);
        } catch (ClientApiException e) {
            log.error("Error getting report templates: {}", e.getMessage(), e);
            throw new ZapApiException("Error getting report templates: " + e.getMessage(), e);
        }
    }

    @Override
    public String generateReport(ReportGenerationRequest request) {
        return generateReport(request, request.contexts(), request.sites());
    }

    @Override
    public String generateScopedReport(ReportGenerationRequest request) {
        List<URI> scopes = Arrays.stream(request.sites().split("\\|", -1)).map(URI::create).toList();
        if (scopes.stream().noneMatch(scope -> scope.getRawPath().equals("/"))) {
            return generateReport(request);
        }
        if (!request.contexts().isBlank()) {
            throw new IllegalArgumentException("Target-scoped reports use their own isolated report context");
        }
        String contextName = "mcp-report-" + UUID.randomUUID();
        boolean created = false;
        Throwable failure = null;
        ZapApiException cleanupFailure = null;
        String reportPath;
        try {
            zap.context.newContext(contextName);
            created = true;
            // A new ZAP context defaults to in-scope. Keep report selection from changing scan scope.
            zap.context.setContextInScope(contextName, "false");
            for (URI scope : scopes) {
                zap.context.includeInContext(contextName, reportScopeRegex(scope));
            }
            // Native report filters use raw startsWith in both alert selection and site grouping.
            // The context enforces exact origins while a bare root also admits empty-path alerts.
            String engineSites = String.join("|", scopes.stream().map(scope -> {
                String site = scope.toString();
                return scope.getRawPath().equals("/") ? site.substring(0, site.length() - 1) : site;
            }).toList());
            reportPath = generateReport(request, contextName, engineSites);
        } catch (ClientApiException e) {
            ZapApiException wrapped = new ZapApiException("Error preparing target-scoped ZAP report", e);
            failure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            if (created) {
                try {
                    zap.context.removeContext(contextName);
                } catch (ClientApiException e) {
                    ZapApiException cleanup = new ZapApiException("Error removing target-scoped report context", e);
                    if (failure != null) {
                        failure.addSuppressed(cleanup);
                    } else {
                        cleanupFailure = cleanup;
                    }
                }
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
        return reportPath;
    }

    private String reportScopeRegex(URI scope) {
        String authority = Pattern.quote(scope.getScheme() + "://" + scope.getHost());
        int defaultPort = scope.getScheme().equals("https") ? 443 : 80;
        String port = scope.getPort() == -1
                ? "(?::" + defaultPort + ")?"
                : ":" + scope.getPort();
        // ZAP compiles context includes case-insensitively; URL paths remain case-sensitive.
        String path = scope.getRawPath().equals("/") ? "(?:[/?#].*)?"
                : "(?-i:" + Pattern.quote(scope.getRawPath()) + ").*";
        return "\\A(?i:" + authority + ")" + port + path + "\\z";
    }

    private String generateReport(ReportGenerationRequest request, String contexts, String sites) {
        try {
            ApiResponse raw = zap.reports.generate(
                    request.title(),
                    request.template(),
                    request.theme(),
                    request.description(),
                    contexts,
                    sites,
                    request.sections(),
                    request.includedConfidences(),
                    request.includedRisks(),
                    request.reportFileName(),
                    request.reportFileNamePattern(),
                    request.reportDirectory(),
                    request.display()
            );
            if (!(raw instanceof ApiResponseElement element)) {
                throw new IllegalStateException("Report generation failed: " + raw);
            }
            return element.getValue();
        } catch (ClientApiException e) {
            log.error("Error generating ZAP report: {}", e.getMessage(), e);
            throw new ZapApiException("Error generating ZAP report: " + e.getMessage(), e);
        }
    }
}
