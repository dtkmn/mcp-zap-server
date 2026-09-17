package mcp.server.zap.core.gateway;

import java.util.List;
import java.util.Map;

/**
 * Gateway-facing access contract for findings emitted by a scan engine.
 */
public interface EngineFindingAccess {

    List<AlertSnapshot> loadAlerts(String baseUrl);

    record AlertSnapshot(
            String id,
            String pluginId,
            String name,
            String description,
            String risk,
            String confidence,
            String url,
            String param,
            String attack,
            String evidence,
            String reference,
            String solution,
            String messageId,
            String cweId,
            String wascId,
            String nodeName,
            String method,
            Map<String, String> tags
    ) {
        public AlertSnapshot {
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }

        public AlertSnapshot(
                String id,
                String pluginId,
                String name,
                String description,
                String risk,
                String confidence,
                String url,
                String param,
                String attack,
                String evidence,
                String reference,
                String solution,
                String messageId,
                String cweId,
                String wascId
        ) {
            this(id, pluginId, name, description, risk, confidence, url, param, attack,
                    evidence, reference, solution, messageId, cweId, wascId, null, null, Map.of());
        }
    }
}
