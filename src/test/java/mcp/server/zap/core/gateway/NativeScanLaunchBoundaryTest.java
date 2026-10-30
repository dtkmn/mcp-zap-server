package mcp.server.zap.core.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import mcp.server.zap.core.gateway.EngineScanExecution.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.AjaxSpider;
import org.zaproxy.clientapi.gen.Ascan;
import org.zaproxy.clientapi.gen.ClientSpider;
import org.zaproxy.clientapi.gen.Core;
import org.zaproxy.clientapi.gen.Spider;

/** Real adapter calls over a fake SDK: no scanner, target or network service is started. */
class NativeScanLaunchBoundaryTest {
    enum Kind { HTTP, HTTP_AS_USER, ACTIVE, ACTIVE_AS_USER, CLIENT, AJAX }
    enum Reply { POSITIVE, LOST, NULL, BLANK, BUSY, BAD_ACK }
    private Spider spider;
    private AjaxSpider ajax;
    private ZapEngineScanExecution execution;
    private ZapEngineAjaxSpiderExecution ajaxExecution;
    private Reply reply;
    private final AtomicBoolean intentPersisted = new AtomicBoolean();
    private final AtomicInteger nativeCalls = new AtomicInteger();
    private final AtomicInteger receipts = new AtomicInteger();
    private final NativeScanLaunch launch = new NativeScanLaunch() {
        @Override public void beforeDispatch() { intentPersisted.set(true); }
        @Override public void accepted(String scanId) {
            assertThat(intentPersisted).isTrue();
            assertThat(nativeCalls).hasValue(1);
            assertThat(scanId).isNotBlank();
            receipts.incrementAndGet();
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        reply = Reply.POSITIVE;
        ClientApi client = mock(ClientApi.class);
        client.core = mock(Core.class);
        client.spider = spider = mock(Spider.class);
        client.ascan = mock(Ascan.class);
        client.clientSpider = mock(ClientSpider.class);
        client.ajaxSpider = ajax = mock(AjaxSpider.class);
        when(client.core.accessUrl(any(), any())).thenReturn(new ApiResponseElement("result", "OK"));
        when(ajax.optionMaxDuration()).thenReturn(new ApiResponseElement("duration", "5"));
        when(spider.scan(any(), any(), any(), any(), any())).thenAnswer(inv -> nativeReply(false));
        when(spider.scanAsUser(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> nativeReply(false));
        when(client.ascan.scan(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> nativeReply(false));
        when(client.ascan.scanAsUser(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> nativeReply(false));
        when(client.clientSpider.scan(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> nativeReply(false));
        when(ajax.scan(any(), any(), any(), any())).thenAnswer(inv -> nativeReply(true));
        execution = new ZapEngineScanExecution(client);
        ajaxExecution = new ZapEngineAjaxSpiderExecution(client);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void allNativeStartFamiliesPersistIntentBeforeEffectAndPublishOnlyPositiveReceipt(Kind kind) {
        assertThat(start(kind)).isNotBlank();
        assertThat(nativeCalls).hasValue(1);
        assertThat(receipts).hasValue(1);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void acceptedRequestWithLostReplyNeverPublishesAnInventedReceipt(Kind kind) {
        reply = Reply.LOST;
        assertThatThrownBy(() -> start(kind)).isInstanceOf(RuntimeException.class);
        assertThat(intentPersisted).isTrue();
        assertThat(nativeCalls).hasValue(1);
        assertThat(receipts).hasValue(0);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void nullNativeReplyDoesNotBecomeAnAcceptedReceipt(Kind kind) {
        reply = Reply.NULL;
        assertThatThrownBy(() -> start(kind)).isInstanceOf(RuntimeException.class);
        assertThat(nativeCalls).hasValue(1);
        assertThat(receipts).hasValue(0);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void blankNativeReplyDoesNotBecomeAnAcceptedReceipt(Kind kind) {
        reply = Reply.BLANK;
        assertThatThrownBy(() -> start(kind)).isInstanceOf(RuntimeException.class);
        assertThat(nativeCalls).hasValue(1);
        assertThat(receipts).hasValue(0);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void explicitBusyReplyRetainsItsTypedKnownRejection(Kind kind) {
        reply = Reply.BUSY;
        assertThatThrownBy(() -> start(kind)).isInstanceOf(EngineBusyException.class);
        assertThat(nativeCalls).hasValue(1);
        assertThat(receipts).hasValue(0);
    }

    @Test
    void preparationFailureDoesNotWriteNativeIntentOrStartWork() throws Exception {
        when(spider.setOptionMaxDepth(4)).thenThrow(new ClientApiException("fake preparation failure"));
        assertThatThrownBy(() -> start(Kind.HTTP)).isInstanceOf(RuntimeException.class);
        assertThat(intentPersisted).isFalse();
        assertThat(nativeCalls).hasValue(0);
    }

    @Test
    void ajaxRequiresAnExplicitPositiveAcknowledgement() {
        reply = Reply.BAD_ACK;
        assertThatThrownBy(() -> start(Kind.AJAX)).hasMessageContaining("positive launch acknowledgement");
        assertThat(receipts).hasValue(0);
    }

    @ParameterizedTest @ValueSource(strings = {"", "unknown", "stopping"})
    void unrecognizedAjaxStatusDoesNotEstablishStoppedWork(String status) throws Exception {
        when(ajax.status()).thenReturn(new ApiResponseElement("status", status));
        assertThatThrownBy(ajaxExecution::readAjaxSpiderStatus).hasMessageContaining("status is unknown");
    }

    private ApiResponse nativeReply(boolean isAjax) throws ClientApiException {
        assertThat(intentPersisted).isTrue();
        nativeCalls.incrementAndGet();
        return switch (reply) {
            case POSITIVE -> new ApiResponseElement(isAjax ? "Result" : "scan", isAjax ? "OK" : "42");
            case LOST -> throw new ClientApiException(new IOException("fake accepted start, lost reply"));
            case NULL -> null;
            case BLANK -> new ApiResponseElement("scan", "");
            case BUSY -> throw new ClientApiException("Scan In Progress", "scan_in_progress", null);
            case BAD_ACK -> new ApiResponseElement("Result", "unexpected");
        };
    }

    private String start(Kind kind) {
        return switch (kind) {
            case HTTP -> execution.startSpiderScan(new SpiderScanRequest("http://fake.invalid", 4, 1, 1, 10), launch);
            case HTTP_AS_USER -> execution.startSpiderScanAsUser(new AuthenticatedSpiderScanRequest("1", "2",
                    "http://fake.invalid", "10", "true", "false", 1, 1, 4), launch);
            case ACTIVE -> execution.startActiveScan(new ActiveScanRequest("http://fake.invalid", "true", null, 1, 1, 1), launch);
            case ACTIVE_AS_USER -> execution.startActiveScanAsUser(new AuthenticatedActiveScanRequest(
                    "1", "2", "http://fake.invalid", "true", null, 1, 1, 1), launch);
            case CLIENT -> execution.startClientSpiderScan(new ClientSpiderScanRequest("http://fake.invalid", 4, 1), launch);
            case AJAX -> ajaxExecution.startAjaxSpider(new EngineAjaxSpiderExecution.AjaxSpiderScanRequest("http://fake.invalid"), launch);
        };
    }
}
