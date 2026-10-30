package mcp.server.zap.core.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import mcp.server.zap.core.configuration.AddonMaintenanceProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TimeoutZapClientApiTest {
    @TempDir Path maintenanceDirectory;
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

    @ParameterizedTest
    @MethodSource("scanStartFailures")
    void generatedScanStartsDoNotReplayAfterTheCompleteRequestLosesItsResponse(ScanStart endpoint, LostReply reply) throws Exception {
        try (LostResponseServer lost = new LostResponseServer(reply)) {
            ClientApi scanClient = new TimeoutZapClientApi("127.0.0.1", lost.port(), "test-key", 500, 200);

            ClientApiException error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> assertThrows(ClientApiException.class, () -> endpoint.start(scanClient)));

            if (reply == LostReply.TIMEOUT_HEADERS || reply == LostReply.TIMEOUT_BODY) {
                assertInstanceOf(SocketTimeoutException.class, error.getCause());
            }
            assertEquals(1, lost.requests.get(), "A possibly accepted scan START must not be sent again");
            assertEquals(1, lost.accepted.get(), "A replay must not hide behind a stalled response handler");
            assertNull(lost.failure.get());
        }
    }

    static Stream<Arguments> scanStartFailures() {
        return Arrays.stream(ScanStart.values())
                .flatMap(endpoint -> Arrays.stream(LostReply.values()).map(reply -> Arguments.of(endpoint, reply)));
    }

    @ParameterizedTest
    @EnumSource(ScanStart.class)
    void generatedScanStartsKeepSuccessfulXmlReplies(ScanStart endpoint) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String value = endpoint == ScanStart.AJAX_SPIDER || endpoint == ScanStart.AJAX_SPIDER_AS_USER ? "OK" : "123";
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            respond(exchange, 200, "<Result>" + value + "</Result>");
        });

        ApiResponseElement result = (ApiResponseElement) endpoint.start(client);

        assertEquals(value, result.getValue());
        assertEquals(1, requests.get());
    }

    @Test
    void generatedScanStartPreservesGetKeyAndEncodedParameters() throws Exception {
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        server.createContext("/", exchange -> {
            received.set(Map.of("key", exchange.getRequestHeaders().getFirst("X-ZAP-API-Key"),
                    "host", exchange.getRequestHeaders().getFirst("Host"),
                    "method", exchange.getRequestMethod(), "query", exchange.getRequestURI().getRawQuery()));
            respond(exchange, 200, "<Result>123</Result>");
        });
        String target = "http://fixture.invalid/a?q=spaces + &unicode=你好";

        assertEquals("123", ((ApiResponseElement) client.ascan.scan(target, "true", "false", "policy + café", null, null)).getValue());

        assertEquals("GET", received.get().get("method"));
        assertEquals("test-key", received.get().get("key"));
        assertEquals("zap", received.get().get("host"));
        assertEquals(target, decode(received.get().get("query")).get("url"));
        assertEquals("policy + café", decode(received.get().get("query")).get("scanPolicyName"));
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 307, 308, 401, 407})
    void scanStartsDoNotFollowRedirectsOrAuthenticationChallenges(int status) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            if (status < 400) exchange.getResponseHeaders().set("Location", "http://zap/xml/ascan/action/scan/");
            if (status == 401) exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"fixture\"");
            if (status == 407) exchange.getResponseHeaders().set("Proxy-Authenticate", "Basic realm=\"fixture\"");
            respond(exchange, status, status < 400 ? "<Result>123</Result>"
                    : "<Result type=\"exception\" code=\"bad_api_key\" detail=\"challenge\">Authentication required</Result>");
        });

        ClientApiException failure = assertThrows(ClientApiException.class, () -> ScanStart.ACTIVE.start(client));

        if (status < 400) assertInstanceOf(IOException.class, failure.getCause());
        else assertEquals("bad_api_key", failure.getCode());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @CsvSource({"xml,GET", "xml,POST", "JSON,GET", "other,GET", "other,POST"})
    void everyScanStartResponseEntryPointUsesTheNoReplayTransport(String format, String method) throws Exception {
        try (LostResponseServer lost = new LostResponseServer(LostReply.EOF)) {
            ClientApi scanClient = new TimeoutZapClientApi("127.0.0.1", lost.port(), "test-key", 500, 200);
            Map<String, String> params = Map.of("url", "http://fixture.invalid");
            Executable start = switch (format) {
                case "xml" -> () -> scanClient.callApi(method, "ascan", "action", "scan", params);
                case "JSON" -> () -> scanClient.callApiJson("ascan", "action", "scan", params);
                case "other" -> () -> scanClient.callApiOther(method, "ascan", "action", "scan", params);
                default -> fail("Unknown response format");
            };
            assertThrows(ClientApiException.class, start);
            assertEquals(1, lost.requests.get());
            assertNull(lost.failure.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyScanStartPostParametersAreNotReplayed(boolean nullParameters) throws Exception {
        try (LostResponseServer lost = new LostResponseServer(LostReply.EOF)) {
            ClientApi scanClient = new TimeoutZapClientApi("127.0.0.1", lost.port(), null, 500, 200);
            assertThrows(ClientApiException.class,
                    () -> scanClient.callApi("POST", "ajaxSpider", "action", "scan", nullParameters ? null : Map.of()));
            assertEquals(1, lost.requests.get());
            assertNull(lost.failure.get());
        }
    }

    @Test
    void scanStartPostPreservesFormEncodingKeyAndErrorCodes() throws Exception {
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/", exchange -> {
            received.set(Map.of("key", exchange.getRequestHeaders().getFirst("X-ZAP-API-Key"),
                    "method", exchange.getRequestMethod(), "contentType", exchange.getRequestHeaders().getFirst("Content-Type")));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 400, "<Result type=\"exception\" code=\"scan_in_progress\" detail=\"busy\">Scan running</Result>");
        });
        Map<String, String> params = new LinkedHashMap<>();
        params.put("url", "http://fixture.invalid/a?q=+ &unicode=你好");
        params.put("optional", null);

        ClientApiException error = assertThrows(ClientApiException.class,
                () -> client.callApi("POST", "spider", "action", "scan", params));

        assertEquals("POST", received.get().get("method"));
        assertEquals("test-key", received.get().get("key"));
        assertEquals("application/x-www-form-urlencoded", received.get().get("contentType"));
        assertEquals(Map.of("url", params.get("url"), "optional", ""), decode(body.get()));
        assertEquals("scan_in_progress", error.getCode());
        assertEquals("busy", error.getDetail());
    }

    @ParameterizedTest
    @CsvSource({"automation,action,runPlan", "openapi,action,importUrl", "reports,action,generate",
            "autoupdate,action,installAddon", "ascan,action,stop", "spider,action,stop", "ajaxSpider,action,stop",
            "clientSpider,action,stop", "ascan,view,status", "clientSpider,action,scanAsUser", "unrelated,action,scan"})
    void otherOperationsKeepTheirGetParameters(String component, String type, String method) throws Exception {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "<Result>OK</Result>");
        });

        assertEquals("OK", ((ApiResponseElement) client.callApi(component, type, method, Map.of("value", "a+b & café"))).getValue());
        assertEquals("GET", receivedMethod.get());
        assertEquals(Map.of("value", "a+b & café"), decode(query.get()));
    }

    @Test
    void maintenanceGateCoversGeneratedXmlPostOtherAndJsonActionsBeforeHttpDispatch() throws Exception {
        AtomicInteger httpRequests = new AtomicInteger();
        server.createContext("/", exchange -> {
            httpRequests.incrementAndGet();
            respond(exchange, 200, "<Result>OK</Result>");
        });
        AddonMaintenanceProperties properties = new AddonMaintenanceProperties();
        properties.setUpdatesEnabled(true);
        properties.setStateDirectory(maintenanceDirectory.toString());
        EngineMaintenanceGate gate = new EngineMaintenanceGate(properties);
        ClientApi guarded = new TimeoutZapClientApi("127.0.0.1", server.getAddress().getPort(), "test-key", 500, 200, gate);
        gate.withMaintenance(() -> {
            var attempts = executor.submit(() -> {
                assertThrows(EngineBusyException.class, () -> guarded.core.accessUrl("http://fixture.invalid", "true"));
                assertThrows(EngineBusyException.class, () -> guarded.callApi("POST", "openapi", "action", "importFile", Map.of()));
                assertThrows(EngineBusyException.class, () -> guarded.callApiOther("reports", "action", "generate", Map.of()));
                assertThrows(EngineBusyException.class, () -> guarded.callApiJson("authentication", "action", "test", Map.of()));
            });
            assertDoesNotThrow(() -> attempts.get(5, TimeUnit.SECONDS));
            return null;
        });
        assertEquals(0, httpRequests.get());
    }

    @Test
    void generatedScanBusyResponseRemainsRetriableWithMaintenanceEnabled() throws Exception {
        server.createContext("/xml/spider/action/scan/", exchange -> respond(exchange, 400,
                "<Result type=\"exception\" code=\"scan_in_progress\" detail=\"busy\">Scan running</Result>"));
        server.createContext("/xml/core/action/accessUrl/", exchange -> respond(exchange, 200, "<Result>OK</Result>"));
        AddonMaintenanceProperties properties = new AddonMaintenanceProperties();
        properties.setUpdatesEnabled(true);
        properties.setStateDirectory(maintenanceDirectory.toString());
        EngineMaintenanceGate gate = new EngineMaintenanceGate(properties);
        ClientApi guarded = new TimeoutZapClientApi("127.0.0.1", server.getAddress().getPort(), "test-key", 500, 200, gate);
        ClientApiException failure = assertThrows(ClientApiException.class,
                () -> guarded.spider.scan("http://fixture.invalid", "1", "true", "", "false"));
        assertEquals("scan_in_progress", failure.getCode());
        assertFalse(gate.isQuarantined());
        assertEquals("OK", ((ApiResponseElement) guarded.core.accessUrl("http://fixture.invalid", "true")).getValue());
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

    private enum ScanStart {
        ACTIVE {
            @Override ApiResponse start(ClientApi api) throws ClientApiException {
                return api.ascan.scan("http://fixture.invalid", "true", "false", "", null, null);
            }
        },
        ACTIVE_AS_USER {
            @Override ApiResponse start(ClientApi api) throws ClientApiException {
                return api.ascan.scanAsUser("http://fixture.invalid", "1", "2", "true", "", null, null);
            }
        },
        HTTP_SPIDER {
            @Override ApiResponse start(ClientApi api) throws ClientApiException {
                return api.spider.scan("http://fixture.invalid", "1", "true", "", "false");
            }
        },
        HTTP_SPIDER_AS_USER {
            @Override ApiResponse start(ClientApi api) throws ClientApiException {
                return api.spider.scanAsUser("1", "2", "http://fixture.invalid", "1", "true", "false");
            }
        },
        AJAX_SPIDER {
            @Override ApiResponse start(ClientApi api) throws ClientApiException {
                return api.ajaxSpider.scan("http://fixture.invalid", "false", "", "false");
            }
        },
        AJAX_SPIDER_AS_USER {
            @Override ApiResponse start(ClientApi api) throws ClientApiException {
                return api.ajaxSpider.scanAsUser("fixture-context", "fixture-user", "http://fixture.invalid", "false");
            }
        },
        CLIENT_SPIDER {
            @Override ApiResponse start(ClientApi api) throws ClientApiException {
                return api.clientSpider.scan("firefox-headless", "http://fixture.invalid", null, null, null, "1", null, "1", null);
            }
        };

        abstract ApiResponse start(ClientApi api) throws ClientApiException;
    }

    private enum LostReply { EOF, RESET, MALFORMED_HEADERS, MALFORMED_XML, TRUNCATED_BODY, TIMEOUT_HEADERS, TIMEOUT_BODY }

    /** Reads the complete request before dropping its response, and keeps accepting to detect a replay. */
    private static final class LostResponseServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket();
        private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
        private final ExecutorService handlers = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicInteger accepted = new AtomicInteger();
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean closed;

        LostResponseServer(LostReply reply) throws IOException {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            acceptor.submit(() -> {
                while (!closed) {
                    try {
                        Socket socket = listener.accept();
                        if (accepted.incrementAndGet() > 8) {
                            socket.close();
                            throw new IOException("Too many fixture connections");
                        }
                        sockets.add(socket);
                        handlers.submit(() -> handle(socket, reply));
                    } catch (SocketException e) {
                        if (!closed) failure.compareAndSet(null, e);
                    } catch (Exception e) {
                        if (!closed) failure.compareAndSet(null, e);
                    }
                }
            });
        }

        private void handle(Socket socket, LostReply reply) {
            try (socket) {
                socket.setSoTimeout(1000);
                InputStream input = socket.getInputStream();
                String headers = readHeaders(input);
                int contentLength = 0;
                for (String line : headers.split("\\r\\n")) {
                    if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                if (contentLength < 0 || contentLength > 65536) throw new IOException("Oversized fixture request body");
                if (input.readNBytes(contentLength).length != contentLength) {
                    throw new EOFException("Incomplete request body");
                }
                requests.incrementAndGet();
                switch (reply) {
                    case EOF -> { }
                    case RESET -> socket.setSoLinger(true, 0);
                    case MALFORMED_HEADERS -> socket.getOutputStream().write("invalid HTTP response\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    case MALFORMED_XML -> socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\n<scan>".getBytes(StandardCharsets.US_ASCII));
                    case TRUNCATED_BODY -> socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n<scan>".getBytes(StandardCharsets.US_ASCII));
                    case TIMEOUT_HEADERS -> release.await(2, TimeUnit.SECONDS);
                    case TIMEOUT_BODY -> {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n<".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                        release.await(2, TimeUnit.SECONDS);
                    }
                }
            } catch (Exception e) {
                if (!closed) failure.compareAndSet(null, e);
            } finally {
                sockets.remove(socket);
            }
        }

        int port() { return listener.getLocalPort(); }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (matched < end.length) {
                int next = input.read();
                if (next < 0) throw new EOFException("Incomplete request headers");
                if (bytes.size() >= 16384) throw new IOException("Oversized fixture request");
                bytes.write(next);
                matched = next == end[matched] ? matched + 1 : next == '\r' ? 1 : 0;
            }
            return bytes.toString(StandardCharsets.ISO_8859_1);
        }

        @Override public void close() throws IOException {
            closed = true;
            release.countDown();
            try {
                listener.close();
                for (Socket socket : sockets) socket.close();
            } finally {
                acceptor.shutdownNow();
                handlers.shutdownNow();
                try {
                    assertTrue(acceptor.awaitTermination(2, TimeUnit.SECONDS));
                    assertTrue(handlers.awaitTermination(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        }
    }
}
