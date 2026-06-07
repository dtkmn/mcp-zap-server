package mcp.server.zap.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.invocation.McpToolInvocationKind;
import org.junit.jupiter.api.Test;

class McpJsonRpcInvocationParserTest {
    private final McpJsonRpcInvocationParser parser = new McpJsonRpcInvocationParser(new ObjectMapper());

    @Test
    void parsesToolCallIntoCoreInvocationContract() {
        McpToolInvocation invocation = parse("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"custom_tool"}}
                """);

        assertThat(invocation.kind()).isEqualTo(McpToolInvocationKind.TOOL_CALL);
        assertThat(invocation.method()).isEqualTo("tools/call");
        assertThat(invocation.toolName()).isEqualTo("custom_tool");
    }

    @Test
    void parsesToolsListIntoCoreInvocationContract() {
        McpToolInvocation invocation = parse("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """);

        assertThat(invocation.kind()).isEqualTo(McpToolInvocationKind.TOOLS_LIST);
        assertThat(invocation.actionName()).isEqualTo("tools/list");
        assertThat(invocation.toolName()).isNull();
    }

    @Test
    void notificationToolCallsStillResolveToToolInvocation() {
        McpToolInvocation invocation = parse("""
                {"jsonrpc":"2.0","method":"tools/call","params":{"name":"notification_tool"}}
                """);

        assertThat(invocation.kind()).isEqualTo(McpToolInvocationKind.TOOL_CALL);
        assertThat(invocation.method()).isEqualTo("tools/call");
        assertThat(invocation.toolName()).isEqualTo("notification_tool");
    }

    @Test
    void jsonRpcResponsesAreUnknown() {
        McpToolInvocation invocation = parse("""
                {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}
                """);

        assertUnknown(invocation);
    }

    @Test
    void missingToolNameIsUnknownButPreservesCallMethod() {
        McpToolInvocation invocation = parse("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{}}
                """);

        assertThat(invocation.kind()).isEqualTo(McpToolInvocationKind.UNKNOWN);
        assertThat(invocation.method()).isEqualTo("tools/call");
        assertThat(invocation.toolName()).isNull();
    }

    @Test
    void blankToolNameIsUnknownButPreservesCallMethod() {
        McpToolInvocation invocation = parse("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"   "}}
                """);

        assertThat(invocation.kind()).isEqualTo(McpToolInvocationKind.UNKNOWN);
        assertThat(invocation.method()).isEqualTo("tools/call");
        assertThat(invocation.toolName()).isNull();
    }

    @Test
    void batchArraysAreUnknown() {
        McpToolInvocation invocation = parse("""
                [{"jsonrpc":"2.0","id":1,"method":"tools/list"}]
                """);

        assertUnknown(invocation);
    }

    @Test
    void nonObjectPayloadsAreUnknown() {
        assertUnknown(parse("null"));
        assertUnknown(parse("\"tools/list\""));
        assertUnknown(parse("42"));
    }

    @Test
    void invalidJsonIsUnknown() {
        McpToolInvocation invocation = parse("{");

        assertUnknown(invocation);
    }

    private McpToolInvocation parse(String payload) {
        return parser.parse(payload.getBytes(StandardCharsets.UTF_8));
    }

    private void assertUnknown(McpToolInvocation invocation) {
        assertThat(invocation.kind()).isEqualTo(McpToolInvocationKind.UNKNOWN);
        assertThat(invocation.method()).isNull();
        assertThat(invocation.toolName()).isNull();
    }
}
