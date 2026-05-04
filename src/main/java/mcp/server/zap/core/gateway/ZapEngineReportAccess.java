package mcp.server.zap.core.gateway;

import java.util.ArrayList;
import java.util.List;
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
        try {
            ApiResponse raw = zap.reports.generate(
                    request.title(),
                    request.template(),
                    request.theme(),
                    request.description(),
                    request.contexts(),
                    request.sites(),
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
