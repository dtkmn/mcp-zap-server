package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution.AjaxSpiderScanRequest;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution.AjaxSpiderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AjaxSpiderServiceTest {

    private EngineAjaxSpiderExecution ajaxSpiderExecution;
    private UrlValidationService urlValidationService;
    private AjaxSpiderService service;

    @BeforeEach
    void setup() {
        ajaxSpiderExecution = mock(EngineAjaxSpiderExecution.class);
        urlValidationService = mock(UrlValidationService.class);
        service = new AjaxSpiderService(ajaxSpiderExecution, urlValidationService);
    }

    @Test
    void startAjaxSpiderJobValidatesTargetAndDelegatesToGatewayExecution() {
        when(ajaxSpiderExecution.startAjaxSpider(any(AjaxSpiderScanRequest.class))).thenReturn("ajax-spider:1");

        String scanId = service.startAjaxSpiderJob("http://target");

        assertThat(scanId).isEqualTo("ajax-spider:1");
        verify(urlValidationService).validateUrl("http://target");
        verify(ajaxSpiderExecution).startAjaxSpider(new AjaxSpiderScanRequest("http://target"));
    }

    @Test
    void getAjaxSpiderStatusFormatsGatewayStatus() {
        when(ajaxSpiderExecution.readAjaxSpiderStatus()).thenReturn(new AjaxSpiderStatus("running", "3", true));

        String result = service.getAjaxSpiderStatus();

        assertThat(result)
                .contains("AJAX Spider Status: running")
                .contains("Pages/URLs discovered: 3")
                .contains("Scan is in progress...");
    }
}
