package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.*;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CoreService {

    private final ClientApi zap;
    private final String contextName = "default-context";
    private final String sessionName = "default-session";

    public CoreService(ClientApi zap) {
        this.zap = zap;
    }

    @Tool(name = "zap_alerts", description = "Retrieve alerts for the given base URL")
    public List<String> getAlerts(@ToolParam(description = "baseUrl") String baseUrl) throws Exception {
        String start = "0";
        String count = "-1";
        ApiResponseList resp = (ApiResponseList) zap.core.alerts(
                baseUrl != null ? baseUrl : "", start, count
        );

        // Build a list of human-readable alert summaries
        List<String> alerts = new ArrayList<>();
        for (ApiResponse item : resp.getItems()) {
            ApiResponseSet set = (ApiResponseSet) item;
            String name = set.getStringValue("alert");
            String risk = set.getStringValue("risk");
            String url  = set.getStringValue("url");
            alerts.add(String.format("%s (risk: %s) at %s", name, risk, url));
        }
        return alerts;
    }

    @Tool(name = "zap_hosts", description = "Retrieve the list of hosts accessed through/by ZAP")
    public List<String> getHosts() throws Exception {
        ApiResponseList resp = (ApiResponseList) zap.core.hosts();
        List<String> hosts = new ArrayList<>();
        for (ApiResponse item : resp.getItems()) {
            hosts.add(((ApiResponseElement) item).getValue());
        }
        return hosts;
    }

    @Tool(name = "zap_sites", description = "Retrieve the list of sites accessed through/by ZAP")
    public List<String> getSites() throws Exception {
        ApiResponseList resp = (ApiResponseList) zap.core.sites();
        List<String> sites = new ArrayList<>();
        for (ApiResponse item : resp.getItems()) {
            sites.add(((ApiResponseElement) item).getValue());
        }
        return sites;
    }

    @Tool(name = "zap_urls", description = "Retrieve the list of URLs accessed through/by ZAP, optionally filtered by base URL")
    public List<String> getUrls(@ToolParam(description = "Base URL to filter (optional)") String baseUrl) throws Exception {
        ApiResponseList resp = (ApiResponseList) zap.core.urls(baseUrl != null ? baseUrl : "");
        List<String> urls = new ArrayList<>();
        for (ApiResponse item : resp.getItems()) {
            urls.add(((ApiResponseElement) item).getValue());
        }
        return urls;
    }

}
