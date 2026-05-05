---
title: "JWT Setup"
editUrl: false
description: "Configure JWT auth, token refresh, revocation, and streamable MCP access."
---
## Overview

The MCP ZAP Server supports JWT authentication for token-based access control with expiration, refresh rotation, and revocation.

This matters most for shared or production deployments where static API keys are too blunt.

## Features

- exchange API keys for JWT access and refresh tokens
- short-lived access tokens and long-lived refresh tokens
- refresh-token rotation with replay rejection
- token revocation
- shared revocation state with either in-memory or Postgres backends
- the same scope model for API-key and JWT clients

## Required Settings

```bash
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
JWT_SECRET=your-256-bit-secret-minimum-32-chars
```

Optional settings:

```bash
JWT_ISSUER=mcp-zap-server
JWT_ACCESS_TOKEN_EXPIRATION=3600
JWT_REFRESH_TOKEN_EXPIRATION=604800

JWT_REVOCATION_STORE_BACKEND=in-memory
JWT_REVOCATION_STORE_POSTGRES_URL=jdbc:postgresql://postgres:5432/mcp_zap
JWT_REVOCATION_STORE_POSTGRES_USERNAME=mcp_user
JWT_REVOCATION_STORE_POSTGRES_PASSWORD=change-me
JWT_REVOCATION_STORE_POSTGRES_TABLE_NAME=jwt_token_revocation
JWT_REVOCATION_STORE_POSTGRES_FAIL_FAST=false
```

## Token Flow

### 1. Exchange API key for tokens

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
  "scopes": ["mcp:tools:list", "zap:scan:read", "zap:report:read"]
}
```

If your local config still uses the legacy wildcard client, the response may show `["*"]`. Replace that with explicit scopes before shared or production use.

### 2. Initialize the MCP session

Manual `curl` tests against `/mcp` must follow the streamable MCP session flow:

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

### 3. Call MCP methods with the session

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

### 4. Refresh the token pair

```bash
curl -X POST http://localhost:7456/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "YOUR_REFRESH_TOKEN"
  }'
```

Refresh tokens are one-time use. Reusing a consumed refresh token is rejected.

### 5. Revoke a token

```bash
curl -X POST http://localhost:7456/auth/revoke \
  -H "Content-Type: application/json" \
  -d '{
    "token": "YOUR_ACCESS_OR_REFRESH_TOKEN"
  }'
```

### 6. Validate a token

```bash
curl -X GET http://localhost:7456/auth/validate \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

Response shape:

```json
{
  "valid": true,
  "tokenId": "uuid-v4",
  "clientId": "default-client",
  "scopes": ["mcp:tools:list", "zap:scan:read", "zap:report:read"],
  "expiresIn": 3520
}
```

## Revocation Backends

### In-memory

Use this for single-node or disposable environments.

Tradeoff:

- simplest setup
- revocation state disappears on restart
- not suitable for multi-replica JWT deployments

### Postgres

Use this when you run multiple MCP replicas and need revocation state shared across them.

Tradeoff:

- shared revocation decisions across replicas
- survives process restarts
- introduces a database dependency

## Scope Model

JWT does not define a separate permission model. The access token inherits the configured client scopes from the API-key client entry.

That means:

- API-key and JWT clients use the same scope vocabulary
- switching from API key to JWT does not require rebuilding your authorization model
- missing tool scopes still produce `403` even when authentication succeeds

For scope setup, see [Tool Scope Authorization](../getting-started/tool-scope-authorization/).

## Recommended Production Baseline

- use JWT for shared or internet-reachable deployments
- keep `MCP_SECURITY_AUTHORIZATION_MODE=enforce`
- move revocation state to Postgres for multi-replica deployments
- rotate API keys and JWT secrets on a schedule and after incidents
- preserve `X-Correlation-Id` in proxies so auth failures stay traceable

## Common Mistakes

### Using fake HTTP endpoints instead of MCP

This server does not expose a custom `/zap/spider/start` HTTP API for normal client use. The runtime surface is `/mcp` plus the auth endpoints under `/auth`.

### Forgetting streamable session bootstrap

If you test `/mcp` with `curl` and skip `initialize`, you are not testing the real protocol flow.

### Assuming JWT replaces authorization

JWT tells the server who you are. Tool scopes still decide what you may do.
