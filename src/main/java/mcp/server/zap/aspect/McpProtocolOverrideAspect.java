package mcp.server.zap.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * This aspect intercepts calls to the protocolVersions() method in any implementation
 * of McpServerTransportProviderBase to enforce the latest protocol version.
 *
 * NOTE: This is an advanced technique and is generally not recommended for managing
 * version compatibility. It can make the system harder to debug and maintain. The
 * preferred approach is to manage dependency versions in the build.gradle file. (which is not available yet)
 */
@Aspect
@Component
public class McpProtocolOverrideAspect {

    /**
     * Intercepts the execution of the protocolVersions() method.
     *
     * @param joinPoint The join point representing the method call.
     * @return A custom list of protocol versions.
     */
    @Around("execution(* io.modelcontextprotocol.spec.McpServerTransportProviderBase.protocolVersion(..))")
    public Object overrideProtocolVersions(ProceedingJoinPoint joinPoint) {
        // This will override the protocol for any bean that implements the interface.
        // The original method is never called.
        return "2025-06-18";
    }

}
