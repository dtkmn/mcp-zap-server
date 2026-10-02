package mcp.server.zap.core.logging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import mcp.server.zap.core.gateway.GatewayCoreAuditAdapter;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestCorrelationWebFilterTest {
    @AfterEach
    void clearRequestContext() {
        RequestCorrelationHolder.clearCorrelationId();
    }

    @Test
    void requestsRejectedBeforeRoutingDoNotCreatePathOrMethodDimensions() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RequestCorrelationWebFilter filter = filter(meters);
        for (int i = 0; i < 300; i++) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method(HttpMethod.valueOf("EXTENSION" + i), URI.create("/random-" + i + ";key=" + i + "?q=" + i)));
            filter.filter(exchange, current -> {
                current.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return Mono.empty();
            }).block();
            assertThat(exchange.getResponse().getHeaders().getFirst(RequestLogContext.CORRELATION_ID_HEADER))
                    .isNotBlank();
        }

        assertThat(meters.find("mcp.zap.http.requests").timers()).hasSize(1);
        assertThat(meters.get("mcp.zap.http.requests")
                .tags("method", "other", "path", "/unmatched", "status", "401")
                .timer().count()).isEqualTo(300);
    }

    @Test
    void annotatedAndFunctionalPatternsAggregateDynamicPaths() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RequestCorrelationWebFilter filter = filter(meters);
        for (String attribute : new String[] {HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                RouterFunctions.MATCHING_PATTERN_ATTRIBUTE}) {
            for (int i = 0; i < 20; i++) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/entries/" + i));
                filter.filter(exchange, current -> {
                    current.getAttributes().put(attribute, PathPatternParser.defaultInstance.parse("/entries/{id}"));
                    current.getResponse().setStatusCode(HttpStatus.OK);
                    return Mono.empty();
                }).block();
            }
        }

        assertThat(meters.find("mcp.zap.http.requests").timers()).hasSize(1);
        assertThat(meters.get("mcp.zap.http.requests").tag("path", "/entries/{id}")
                .timer().count()).isEqualTo(40);
    }

    @Test
    void configuredMcpAndActuatorRoutesKeepUsefulLabels() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RequestCorrelationWebFilter filter = filter(meters);
        for (String pattern : new String[] {"/gateway/mcp", "/auth/token", "/actuator/metrics/{requiredMetricName}"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(pattern.replace("{requiredMetricName}", "some.metric")));
            filter.filter(exchange, current -> {
                current.getAttributes().put(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        PathPatternParser.defaultInstance.parse(pattern));
                current.getAttributes().put(RequestLogContext.CLIENT_ID_ATTRIBUTE, "test-client");
                current.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                return Mono.empty();
            }).block();
        }

        assertThat(meters.get("mcp.zap.http.requests").tags("path", "/gateway/mcp", "authenticated", "true")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("mcp.zap.http.requests").tag("path", "/auth/token").timer().count()).isEqualTo(1);
        assertThat(meters.get("mcp.zap.http.requests").tag("path", "/actuator/metrics/{name}").timer().count()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private RequestCorrelationWebFilter filter(SimpleMeterRegistry meters) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(meters);
        return new RequestCorrelationWebFilter(new ObservabilityService(provider, event -> {},
                mock(ClientWorkspaceResolver.class), mock(GatewayCoreAuditAdapter.class)));
    }
}
