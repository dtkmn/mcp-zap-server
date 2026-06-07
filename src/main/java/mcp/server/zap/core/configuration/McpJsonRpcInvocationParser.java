package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.gateway.core.invocation.McpToolInvocation;

final class McpJsonRpcInvocationParser {
    private final ObjectMapper objectMapper;

    McpJsonRpcInvocationParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    McpToolInvocation parse(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return McpToolInvocation.unknown();
        }

        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            if (root == null || !root.isObject()) {
                return McpToolInvocation.unknown();
            }

            String method = textValue(root.get("method"));
            JsonNode params = root.get("params");
            String toolName = params != null ? textValue(params.get("name")) : null;
            return McpToolInvocation.fromJsonRpc(method, toolName);
        } catch (Exception e) {
            return McpToolInvocation.unknown();
        }
    }

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }
}
