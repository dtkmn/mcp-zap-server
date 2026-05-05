package mcp.server.zap.core.configuration;

import io.micrometer.context.ContextRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import mcp.server.zap.core.logging.RequestCorrelationHolder;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Enables Reactor thread-local context propagation so request authentication is
 * available to synchronous MCP tool implementations that still rely on
 * SecurityContextHolder.
 */
@Configuration(proxyBeanMethods = false)
public class ReactiveContextPropagationConfiguration {
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    public ReactiveContextPropagationConfiguration() {
        if (ENABLED.compareAndSet(false, true)) {
            ContextRegistry.getInstance().loadThreadLocalAccessors();
            ContextRegistry.getInstance().registerThreadLocalAccessor(
                    RequestIdentityHolder.CLIENT_ID_KEY,
                    RequestIdentityHolder::currentClientId,
                    RequestIdentityHolder::setClientId,
                    RequestIdentityHolder::clearClientId
            );
            ContextRegistry.getInstance().registerThreadLocalAccessor(
                    RequestIdentityHolder.WORKSPACE_ID_KEY,
                    RequestIdentityHolder::currentWorkspaceId,
                    RequestIdentityHolder::setWorkspaceId,
                    RequestIdentityHolder::clearWorkspaceId
            );
            ContextRegistry.getInstance().registerThreadLocalAccessor(
                    RequestCorrelationHolder.CORRELATION_ID_KEY,
                    RequestCorrelationHolder::currentCorrelationId,
                    RequestCorrelationHolder::setCorrelationId,
                    RequestCorrelationHolder::clearCorrelationId
            );
            Hooks.enableAutomaticContextPropagation();
        }
    }
}
