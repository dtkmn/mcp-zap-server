package mcp.server.zap.core.gateway;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseFactory;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Applies per-connection timeouts to the SDK's generated API endpoints. ClientApi 1.17.0
 * keeps its HTTP transport private, so the three public response entry points are overridden.
 * Scan START requests use an explicitly retry-disabled transport: losing their reply must not
 * transmit a second START. Other operations retain the existing transport behavior.
 */
public final class TimeoutZapClientApi extends ClientApi {
    private final Proxy proxy;
    private final HttpHost scanStartProxy;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final EngineMaintenanceGate maintenanceGate;

    public TimeoutZapClientApi(String host, int port, String apiKey, int connectTimeoutMs, int readTimeoutMs) {
        this(host, port, apiKey, connectTimeoutMs, readTimeoutMs, null);
    }

    public TimeoutZapClientApi(String host, int port, String apiKey, int connectTimeoutMs, int readTimeoutMs,
                               EngineMaintenanceGate maintenanceGate) {
        super(host, port, apiKey);
        this.maintenanceGate = maintenanceGate;
        this.connectTimeoutMs = requirePositive(connectTimeoutMs, "connect-timeout-ms");
        this.readTimeoutMs = requirePositive(readTimeoutMs, "read-timeout-ms");
        this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        this.scanStartProxy = new HttpHost("http", host, port);
        this.apiKey = apiKey;
    }

    @Override
    public ApiResponse callApi(String requestMethod, String component, String type, String method,
                               Map<String, String> params) throws ClientApiException {
        return guarded(component, type, method, params, () -> callXml(requestMethod, component, type, method, params));
    }

    private ApiResponse callXml(String requestMethod, String component, String type, String method,
                               Map<String, String> params) throws ClientApiException {
        byte[] response = request(requestMethod, "xml", component, type, method, params);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response));
            return ApiResponseFactory.getResponse(document.getDocumentElement());
        } catch (ClientApiException e) {
            // Keep ZAP error codes (including scan_in_progress) available to the adapters.
            throw e;
        } catch (Exception e) {
            throw new ClientApiException(e);
        }
    }

    @Override
    public byte[] callApiOther(String requestMethod, String component, String type, String method,
                               Map<String, String> params) throws ClientApiException {
        return guarded(component, type, method, params, () -> request(requestMethod, "other", component, type, method, params));
    }

    @Override
    public String callApiJson(String component, String type, String method, Map<String, String> params)
            throws ClientApiException {
        return guarded(component, type, method, params,
                () -> new String(request("GET", "JSON", component, type, method, params), StandardCharsets.UTF_8));
    }

    private <T> T guarded(String component, String type, String method, Map<String, String> params,
                          EngineMaintenanceGate.ApiCall<T> call)
            throws ClientApiException {
        return maintenanceGate == null ? call.call() : maintenanceGate.apiCall(component, type, method, params, call);
    }

    private byte[] request(String requestMethod, String format, String component, String type, String method,
                           Map<String, String> params) throws ClientApiException {
        HttpURLConnection connection = null;
        try {
            StringJoiner encoded = new StringJoiner("&");
            if (params != null) {
                params.forEach((key, value) -> encoded.add(encode(key) + "=" + encode(value == null ? "" : value)));
            }
            boolean get = "GET".equals(requestMethod);
            String url = "http://zap/" + format + "/" + component + "/" + type + "/" + method + "/";
            if (get && params != null) {
                url += "?" + encoded;
            }
            if (isScanStart(component, type, method)) {
                return requestScanStart(requestMethod, url, encoded.toString());
            }
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection(proxy);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setUseCaches(false);
            connection.setRequestMethod(requestMethod);
            if (apiKey != null && !apiKey.isEmpty()) {
                connection.setRequestProperty("X-ZAP-API-Key", apiKey);
            }
            if (!get && encoded.length() > 0) {
                byte[] body = encoded.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (var output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            try (InputStream input = status >= HttpURLConnection.HTTP_BAD_REQUEST
                    ? connection.getErrorStream() : connection.getInputStream()) {
                if (input == null) {
                    throw new IOException("ZAP returned HTTP " + status + " without a response body");
                }
                return input.readAllBytes();
            }
        } catch (Exception e) {
            throw new ClientApiException(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] requestScanStart(String requestMethod, String url, String encoded) throws IOException {
        BasicHttpClientConnectionManager manager = new BasicHttpClientConnectionManager();
        manager.setConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build());
        RequestConfig config = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .setAuthenticationEnabled(false)
                .build();
        // One request owns this connection manager; closing the client also closes its sockets.
        try (var client = HttpClients.custom()
                .setConnectionManager(manager)
                .setProxy(scanStartProxy)
                .setDefaultRequestConfig(config)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .build()) {
            HttpUriRequestBase request = new HttpUriRequestBase(requestMethod, URI.create(url));
            if (apiKey != null && !apiKey.isEmpty()) {
                request.setHeader("X-ZAP-API-Key", apiKey);
            }
            if (!"GET".equals(requestMethod)) {
                request.setEntity(new ByteArrayEntity(encoded.getBytes(StandardCharsets.UTF_8),
                        ContentType.APPLICATION_FORM_URLENCODED));
                request.setHeader("Content-Type", "application/x-www-form-urlencoded");
            }
            return client.execute(request, response -> {
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    throw new IOException("ZAP scan START returned HTTP " + status + "; redirect was not followed");
                }
                if (response.getEntity() == null) {
                    throw new IOException("ZAP returned HTTP " + status + " without a response body");
                }
                return EntityUtils.toByteArray(response.getEntity());
            });
        }
    }

    private static boolean isScanStart(String component, String type, String method) {
        if (!"action".equals(type)) return false;
        if ("clientSpider".equals(component)) return "scan".equals(method);
        return ("scan".equals(method) || "scanAsUser".equals(method))
                && ("ascan".equals(component) || "spider".equals(component) || "ajaxSpider".equals(component));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException("zap.server." + property + " must be positive");
        }
        return value;
    }
}
