package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * CoreService provides methods to interact with the ZAP API for retrieving alerts, hosts, sites, and URLs.
 * It uses the ZAP client API to perform these operations.
 */
@Slf4j
@Service
public class CoreService {

    private final ClientApi zap;

    /**
     * Build-time dependency injection constructor.
     */
    public CoreService(ClientApi zap) {
        this.zap = zap;
    }

    /**
     * Retrieve alerts for a given base URL.
     *
     * @param baseUrl The base URL to filter alerts (optional).
     * @return A list of alert summaries.
     */
    public List<String> getAlerts(String baseUrl) {
        try {
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
                String url = set.getStringValue("url");
                alerts.add(String.format("%s (risk: %s) at %s", name, risk, url));
            }
            return alerts;
        } catch (ClientApiException e) {
            log.error("Failed to retrieve alerts for base URL: {}: {}", baseUrl, e.getMessage(), e);
            throw new ZapApiException("Failed to retrieve alerts", e);
        }
    }

    /**
     * Retrieve the list of hosts accessed through/by ZAP.
     *
     * @return A list of host names.
     */
    public List<String> getHosts() {
        try {
            ApiResponseList resp = (ApiResponseList) zap.core.hosts();
            List<String> hosts = new ArrayList<>();
            for (ApiResponse item : resp.getItems()) {
                hosts.add(((ApiResponseElement) item).getValue());
            }
            return hosts;
        } catch (ClientApiException e) {
            log.error("Failed to retrieve hosts: {}", e.getMessage(), e);
            throw new ZapApiException("Failed to retrieve hosts", e);
        }
    }

    /**
     * Retrieve the list of sites accessed through/by ZAP.
     *
     * @return A list of site URLs.
     */
    public List<String> getSites() {
        try {
            ApiResponseList resp = (ApiResponseList) zap.core.sites();
            List<String> sites = new ArrayList<>();
            for (ApiResponse item : resp.getItems()) {
                sites.add(((ApiResponseElement) item).getValue());
            }
            return sites;
        } catch (ClientApiException e) {
            log.error("Failed to retrieve sites: {}", e.getMessage(), e);
            throw new ZapApiException("Failed to retrieve sites", e);
        }
    }

    /**
     * Retrieve the list of URLs accessed through/by ZAP, optionally filtered by base URL.
     *
     * @param baseUrl The base URL to filter URLs (optional).
     * @return A list of URLs.
     */
    public List<String> getUrls(String baseUrl) {
        try {
            ApiResponseList resp = (ApiResponseList) zap.core.urls(baseUrl != null ? baseUrl : "");
            List<String> urls = new ArrayList<>();
            for (ApiResponse item : resp.getItems()) {
                urls.add(((ApiResponseElement) item).getValue());
            }
            return urls;
        } catch (ClientApiException e) {
            log.error("Failed to retrieve URLs for base URL: {}: {}", baseUrl, e.getMessage(), e);
            throw new ZapApiException("Failed to retrieve URLs", e);
        }
    }

}
