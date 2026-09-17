package mcp.server.zap.core.gateway;

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
 */
public final class TimeoutZapClientApi extends ClientApi {
    private final Proxy proxy;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public TimeoutZapClientApi(String host, int port, String apiKey, int connectTimeoutMs, int readTimeoutMs) {
        super(host, port, apiKey);
        this.connectTimeoutMs = requirePositive(connectTimeoutMs, "connect-timeout-ms");
        this.readTimeoutMs = requirePositive(readTimeoutMs, "read-timeout-ms");
        this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        this.apiKey = apiKey;
    }

    @Override
    public ApiResponse callApi(String requestMethod, String component, String type, String method,
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
        return request(requestMethod, "other", component, type, method, params);
    }

    @Override
    public String callApiJson(String component, String type, String method, Map<String, String> params)
            throws ClientApiException {
        return new String(request("GET", "JSON", component, type, method, params), StandardCharsets.UTF_8);
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
