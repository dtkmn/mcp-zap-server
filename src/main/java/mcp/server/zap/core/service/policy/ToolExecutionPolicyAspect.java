package mcp.server.zap.core.service.policy;

import mcp.server.zap.core.logging.RequestCorrelationHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.CodeSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Shared runtime policy hook that can move from off to dry-run to enforce without
 * changing the public tool surface.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ToolExecutionPolicyAspect {
    private final ToolExecutionPolicyService toolExecutionPolicyService;

    public ToolExecutionPolicyAspect(ToolExecutionPolicyService toolExecutionPolicyService) {
        this.toolExecutionPolicyService = toolExecutionPolicyService;
    }

    @Around("@annotation(tool)")
    public Object enforcePolicy(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        String toolName = tool != null && tool.name() != null && !tool.name().isBlank()
                ? tool.name().trim()
                : joinPoint.getSignature().getName();
        toolExecutionPolicyService.enforce(
                toolName,
                extractTarget(joinPoint),
                RequestCorrelationHolder.currentCorrelationId()
        );
        return joinPoint.proceed();
    }

    private String extractTarget(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof CodeSignature codeSignature)) {
            return null;
        }
        String[] parameterNames = codeSignature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        for (int index = 0; index < parameterNames.length; index++) {
            String parameterName = parameterNames[index];
            Object value = args[index];
            if (!(value instanceof String candidate) || candidate.isBlank()) {
                continue;
            }
            if ("target".equals(parameterName)
                    || "targetUrl".equals(parameterName)
                    || "baseUrl".equals(parameterName)
                    || "endpointUrl".equals(parameterName)) {
                return candidate.trim();
            }
        }
        return null;
    }
}
