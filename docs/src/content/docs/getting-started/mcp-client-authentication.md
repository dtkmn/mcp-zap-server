---
title: "MCP Client Authentication"
editUrl: false
description: "Configure MCP clients for API-key or bearer-token access to the streamable HTTP endpoint."
---
This server exposes a streamable HTTP MCP endpoint at:

```text
http://localhost:7456/mcp
```

The server supports:

- API key authentication via `X-API-Key`
- bearer tokens via `Authorization: Bearer ...`
- JWT minting and refresh on the server side

The practical truth: API-key mode is still the easiest self-serve client setup. Not every MCP desktop client handles remote HTTP transport, custom auth headers, or JWT refresh the same way.

If you are starting from a fresh clone, use [Self-Serve First Run](./self-serve-first-run/) before tuning client-specific details here.

## Recommended Paths

Use one of these paths:

1. bundled Open WebUI for the easiest local setup
2. Cursor or another MCP client that supports streamable HTTP plus custom headers
3. a custom client or script that can call the MCP endpoint directly

This repository does not ship first-party desktop proxy helpers. Older proxy examples were fiction-by-documentation and needed to die.

## Open WebUI

The default Compose stack already wires Open WebUI to the local MCP server:

```yaml
services:
  open-webui:
    environment:
      TOOL_SERVER_CONNECTIONS: >-
        [{"url":"http://mcp-server:7456/mcp","path":"","type":"mcp","auth_type":"none","headers":{"X-API-Key":"${MCP_API_KEY}"},"config":{"enable":true},"info":{"id":"zap-security","name":"ZAP Security","description":"OWASP ZAP tools exposed by the MCP server."}}]
```

For the bundled Open WebUI deployment, API-key mode is the recommended authentication model.

If you switch the server to JWT mode, Open WebUI can send a pre-issued bearer token, but it does not mint or refresh tokens for you.

## Cursor

Cursor supports remote MCP servers over streamable HTTP. Use Cursor's MCP configuration file and point it directly at this server.

Typical config locations:

- project-specific: `.cursor/mcp.json`
- user-wide: `~/.cursor/mcp.json`

Example using API-key mode:

```json
{
  "mcpServers": {
    "zap-security": {
      "url": "http://localhost:7456/mcp",
      "headers": {
        "X-API-Key": "${env:MCP_API_KEY}"
      }
    }
  }
}
```

If you prefer a static bearer token instead of `X-API-Key`:

```json
{
  "mcpServers": {
    "zap-security": {
      "url": "http://localhost:7456/mcp",
      "headers": {
        "Authorization": "Bearer ${env:MCP_BEARER_TOKEN}"
      }
    }
  }
}
```

Use API-key mode unless you already have a reason to manage token issuance outside Cursor.

## Generic Streamable HTTP Clients

Any MCP client that supports:

- remote streamable HTTP transport
- custom request headers

can usually connect with a config like this:

```json
{
  "mcpServers": {
    "zap-security": {
      "protocol": "mcp",
      "transport": "streamable-http",
      "url": "http://localhost:7456/mcp",
      "headers": {
        "X-API-Key": "your-mcp-api-key-here"
      }
    }
  }
}
```

If your client cannot send custom headers for a remote MCP server, this repo does not currently provide a first-party workaround.

## Claude Desktop

Claude Desktop's remote MCP flow is different from Cursor's:

- remote servers are configured through Claude's `Settings > Connectors` flow
- Anthropic's remote MCP guidance is not the same thing as old `claude_desktop_config.json` examples floating around online
- this repository currently ships API-key and JWT auth, not an OAuth-style Claude connector flow

Because of that, Open WebUI or Cursor is the recommended self-serve path today.

If you need first-class Claude Desktop remote onboarding, the right fix is not another ad hoc proxy snippet. The right fix is a supported auth path that matches Claude's connector model.

## JWT Guidance

JWT is supported by the server, but desktop-client ergonomics depend on the client:

- if the client can send a pre-issued bearer token, it can talk to this server
- if the client cannot mint or refresh tokens, you still need another system to manage token lifecycle
- this repository does not currently ship token-refresh helper scripts for desktop clients

That is why API-key mode remains the recommended self-serve option for local Compose, Open WebUI, and Cursor.

For server-side JWT setup, see [JWT Setup](../security-modes/jwt-authentication/).

## Test The Endpoint Manually

API-key example:

```bash
SESSION_ID=$(curl -si \
  -H "X-API-Key: your-mcp-api-key" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0.0"}}}' \
  | awk -F': ' '/Mcp-Session-Id/ {print $2}' | tr -d '\r')

curl -H "X-API-Key: your-mcp-api-key" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

JWT example:

```bash
TOKEN_RESPONSE=$(curl -s -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"your-mcp-api-key","clientId":"default-client"}')

ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.accessToken')

SESSION_ID=$(curl -si \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0.0"}}}' \
  | awk -F': ' '/Mcp-Session-Id/ {print $2}' | tr -d '\r')

curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

## Troubleshooting

### 401 Unauthorized

- verify the server is in the auth mode you expect
- verify the client is sending `X-API-Key` or `Authorization`
- verify the MCP server URL includes `/mcp`

### Client Connects But Tools Fail

- initialize the MCP session first if you are testing manually
- reuse the returned `Mcp-Session-Id` header on later requests

### Claude Desktop Setup Feels Inconsistent

That is because Claude's remote connector flow and generic JSON config examples on the internet are not the same thing. For this repository today, prefer Open WebUI or Cursor unless you are intentionally building a Claude-specific remote connector path.
