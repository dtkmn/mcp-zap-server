package mcp.server.zap.core.gateway;

import java.util.List;

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
            String wascId
    ) {
    }
}
