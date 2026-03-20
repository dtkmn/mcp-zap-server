---
title: "Quick Start: JWT Authentication"
editUrl: false
description: "Mint a JWT, initialize an MCP session, and verify authenticated access."
---
Get started with JWT authentication in a few minutes.

## Prerequisites

- MCP ZAP Server running
- `curl`
- `jq` if you want to use the copy-paste shell snippets as written

JWT is not the fastest local setup path. Use it when you need token expiry, refresh rotation, revocation, or a shared production auth model.

## Step 1: Configure JWT Mode

Set these values in `.env`:

```bash
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
MCP_API_KEY=your-initial-api-key
```

## Step 2: Generate JWT Secret

```bash
openssl rand -base64 32
```

Then add it to `.env`:

```bash
JWT_SECRET=your-generated-secret-key-here
```

## Step 3: Restart Services

```bash
docker compose down
docker compose up -d
```

Or if running locally:

```bash
./gradlew bootRun
```

## Step 4: Get JWT Tokens

```bash
curl -s -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{
    "apiKey": "your-mcp-api-key",
    "clientId": "default-client"
  }'
```

Response shape:

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "clientId": "default-client",
  "scopes": ["*"]
}
```

## Step 5: Initialize The MCP Session

Do not skip this. `/mcp` is streamable HTTP, so manual testing requires `initialize` first.

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
```

## Step 6: Verify MCP Access

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

## Step 7: Refresh Token

When the access token expires, use the refresh token to get a new token pair:

```bash
REFRESH_TOKEN="eyJ..."

curl -X POST http://localhost:7456/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\": \"$REFRESH_TOKEN\"}"
```

The returned refresh token replaces the old one. Refresh tokens are one-time use.

## Step 8: Revoke Token

```bash
curl -X POST http://localhost:7456/auth/revoke \
  -H "Content-Type: application/json" \
  -d '{
    "token": "YOUR_ACCESS_OR_REFRESH_TOKEN"
  }'
```

## Troubleshooting

### "JWT secret is not configured"

Set `JWT_SECRET` and restart the service.

### "JWT secret must be at least 256 bits"

Generate a stronger secret:

```bash
openssl rand -base64 32
```

### "Invalid API key"

Check that `MCP_API_KEY` matches the configured client entry.

### "I get 401 or 403 on /mcp even with a token"

Common causes:

- you forgot to initialize the MCP session first
- you did not send back `Mcp-Session-Id`
- your client scopes do not allow `tools/list` or the tool you are calling

## Next Steps

- read the [full JWT guide](../security-modes/jwt-authentication/)
- configure client scopes with [Tool Scope Authorization](./tool-scope-authorization/)
- choose the right [Tool Surface](./tool-surfaces/)
- configure your real client with [MCP Client Authentication](./mcp-client-authentication/)
