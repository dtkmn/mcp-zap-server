package mcp.server.zap.core.observability;

import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import mcp.server.zap.core.logging.RequestCorrelationHolder;

/**
 * Measures all MCP tool method executions and emits audit events for success and
 * failure outcomes.
 */
@Aspect
@Component
public class ToolExecutionObservabilityAspect {
    private final ObservabilityService observabilityService;

    public ToolExecutionObservabilityAspect(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @Around("@annotation(tool)")
    public Object observeToolExecution(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        String toolName = tool != null && tool.name() != null && !tool.name().isBlank()
                ? tool.name().trim()
                : joinPoint.getSignature().getName();
        long startedAtNanos = System.nanoTime();
        String correlationId = RequestCorrelationHolder.currentCorrelationId();

        try {
            Object result = joinPoint.proceed();
            observabilityService.recordToolExecution(
                    toolName,
                    "success",
                    Duration.ofNanos(System.nanoTime() - startedAtNanos),
                    correlationId,
                    null
            );
            return result;
        } catch (IllegalArgumentException e) {
            observabilityService.recordToolExecution(
                    toolName,
                    "invalid_request",
                    Duration.ofNanos(System.nanoTime() - startedAtNanos),
                    correlationId,
                    e
            );
            throw e;
        } catch (Throwable t) {
            observabilityService.recordToolExecution(
                    toolName,
                    "failure",
                    Duration.ofNanos(System.nanoTime() - startedAtNanos),
                    correlationId,
                    t
            );
            throw t;
        }
    }
}
