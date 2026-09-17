package mcp.server.zap.core.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TimeoutZapClientApiTest {
    private HttpServer server;
    private ExecutorService executor;
    private ClientApi client;
    private final CountDownLatch releaseResponse = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        client = new TimeoutZapClientApi("127.0.0.1", server.getAddress().getPort(), "test-key", 500, 200);
    }

    @AfterEach
    void tearDown() {
        releaseResponse.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void generatedEndpointsPreserveProxyRoutingKeyAndEncodedParameters() throws Exception {
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        server.createContext("/xml/core/action/accessUrl/", exchange -> {
            received.set(Map.of("key", exchange.getRequestHeaders().getFirst("X-ZAP-API-Key"),
                    "host", exchange.getRequestHeaders().getFirst("Host"),
                    "method", exchange.getRequestMethod(), "query", exchange.getRequestURI().getRawQuery()));
            respond(exchange, 200, "<Result>OK</Result>");
        });
        String target = "http://example.test/a?q=spaces + &unicode=你好";
        ApiResponseElement result = (ApiResponseElement) client.core.accessUrl(target, "true");

        assertEquals("OK", result.getValue());
        assertEquals("test-key", received.get().get("key"));
        assertEquals("zap", received.get().get("host"));
        assertEquals("GET", received.get().get("method"));
        assertEquals(Map.of("url", target, "followRedirects", "true"), decode(received.get().get("query")));
    }

    @Test
    void postRequestsKeepFormBodyAndXmlErrorCodes() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            method.set(exchange.getRequestMethod());
            respond(exchange, 400, "<Result type=\"exception\" code=\"scan_in_progress\" detail=\"busy\">Scan running</Result>");
        });
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contents", "a+b & café\nsecond line");
        params.put("optional", null);

        ClientApiException error = assertThrows(ClientApiException.class,
                () -> client.callApi("POST", "automation", "action", "runPlan", params));

        assertEquals("POST", method.get());
        assertEquals(Map.of("contents", params.get("contents"), "optional", ""), decode(body.get()));
        assertEquals("scan_in_progress", error.getCode());
        assertEquals("busy", error.getDetail());
        assertEquals("Scan running", error.getMessage());
    }

    @Test
    void otherAndJsonResponsesRetainTheirBytes() throws Exception {
        byte[] report = {0, 1, 2, (byte) 255};
        server.createContext("/other/", exchange -> {
            exchange.sendResponseHeaders(200, report.length);
            try (var output = exchange.getResponseBody()) {
                output.write(report);
            }
        });
        server.createContext("/JSON/", exchange -> respond(exchange, 200, "{\"name\":\"café\"}"));

        assertArrayEquals(report, client.callApiOther("core", "other", "report", null));
        assertEquals("{\"name\":\"café\"}", client.callApiJson("core", "view", "version", Map.of()));
    }

    @ParameterizedTest
    @CsvSource({"xml,false", "xml,true", "other,false", "other,true", "JSON,false", "JSON,true"})
    void stalledHeadersAndBodiesTimeOutWithoutRetrying(String format, boolean sendPartialBody) {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch requestReceived = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestReceived.countDown();
            try (exchange) {
                if (sendPartialBody) {
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write('<');
                    exchange.getResponseBody().flush();
                }
                try {
                    releaseResponse.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        ClientApiException error = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(ClientApiException.class, () -> {
                    switch (format) {
                        case "xml" -> client.core.version();
                        case "other" -> client.callApiOther("core", "other", "report", null);
                        case "JSON" -> client.callApiJson("core", "view", "version", null);
                        default -> fail("Unknown response format");
                    }
                }));

        assertEquals(0, requestReceived.getCount());
        assertInstanceOf(SocketTimeoutException.class, error.getCause());
        assertEquals(1, requests.get());
        assertEquals(1, releaseResponse.getCount(), "Request returned while the server still withheld data");
    }

    @Test
    void rejectsXmlExternalEntities() {
        server.createContext("/", exchange -> respond(exchange, 200,
                "<!DOCTYPE version [<!ENTITY xxe SYSTEM 'http://127.0.0.1/forbidden'>]><version>&xxe;</version>"));

        ClientApiException error = assertThrows(ClientApiException.class, () -> client.core.version());

        assertTrue(error.getCause().getMessage().contains("DOCTYPE"));
    }

    private static Map<String, String> decode(String encoded) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
