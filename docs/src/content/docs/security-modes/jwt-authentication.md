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
- local in-memory revocation state or shared Postgres revocation state
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
# Default in v0.14.0; v0.13.0 defaults to false.
JWT_REVOCATION_STORE_POSTGRES_FAIL_FAST=true
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

Apply the bundled, opt-in Flyway migrations to the revocation database before
starting replicas. Set `DB_MIGRATIONS_ENABLED=true` and the
`DB_MIGRATIONS_POSTGRES_URL`, `DB_MIGRATIONS_POSTGRES_USERNAME`, and
`DB_MIGRATIONS_POSTGRES_PASSWORD` settings to the same database used by
`JWT_REVOCATION_STORE_POSTGRES_*`. The migrations create the default
`jwt_token_revocation` table; a custom table must match that schema.

<a id="development-postgresql-failure-behavior"></a>

### PostgreSQL failure behavior in v0.14.0

**Version `v0.14.0` change; not included in `v0.13.0`.** Check
[GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) for availability.
PostgreSQL is authoritative when
selected: the server never falls back to process-local revocation state. An
unknown backend, missing PostgreSQL URL, invalid table name, or missing or
inaccessible revocation table prevents startup. `JWT_REVOCATION_STORE_POSTGRES_FAIL_FAST` now defaults
to `true`; the previous default was `false`.

Revocation lookups, refresh-token consumption, and revocation writes must succeed
in PostgreSQL even when `failFast=false`. Backend failures return a sanitized
HTTP `503` for protected JWT requests and `/auth/refresh`, `/auth/revoke`, and
`/auth/validate`. A failing or uncertain write produces no token pair or success
acknowledgment. `/auth/token` and registered API-key authentication do not depend
on the revocation database.

Previously persisted revocations and refresh-token consumption remain effective
across replicas after database recovery. Retry an unsuccessful revocation after
recovery; a failed request does not confirm a durable revocation. If a refresh
write committed but its result could not be confirmed, the refresh token may be
consumed without a token pair being delivered. A retry may return `401`; exchange
the registered API key for a new pair if needed.

Setting `failFast=false` only tolerates failures during explicit cleanup of
expired records. It never relaxes JWT enforcement or required writes. A cleanup
failure after a revocation has been persisted does not negate that successful
revocation. Explicit `in-memory` mode remains local to one process and loses
state on restart.

<a id="development-jwt-expiration-and-revocation-lifetime"></a>

### JWT expiration and revocation lifetime in v0.14.0

**Version `v0.14.0` change; not included in `v0.13.0`.** JWT validation and both revocation
backends use the same existing 60-second clock-skew allowance. Revocations and
used refresh-token records remain effective through 60 seconds after the token's
original `exp`, including that boundary. They become inactive and eligible for
cleanup only after it. A token that expires beyond this allowance while its
revocation check or refresh consumption is in progress is rejected. Stored `exp`
values remain unchanged; no schema migration
or expiry backfill is required. Token expiration claims, response expiry fields,
and registered API-key behavior are unchanged.

JWTs without an `exp` claim are now rejected. Server-issued access and refresh
tokens already include it. Keep replica and database clocks synchronized.

#### Upgrade and recover safely

Patched processes apply the corrected lifetime comparisons to surviving
PostgreSQL records, but the upgrade cannot reconstruct records already deleted.
Stop all older replicas, token writers, and cleanup processes before treating
the fleet as protected; mixed versions can still discard required revocation
state.

If state is missing or untrusted, including after an in-memory restart, hold
traffic and stop the older processes. Deploy the patched fleet with a new shared
`JWT_SECRET` using the [JWT Key Rotation Runbook](../jwt-key-rotation-runbook/),
then resume traffic and obtain new token pairs through registered API keys. The
new secret invalidates tokens signed with the previous key.

If keeping PostgreSQL state and the existing signing key, and the only known
state loss is expired-row cleanup, hold traffic for **strictly more than 60
seconds after the final older writer or cleanup process stops**, plus a margin
for clock differences. This drains only that expiry gap; it does not cover lost
revocations for future-expiring tokens or lost in-memory state. If waiting for
untrusted tokens to expire instead of invalidating them, use the maximum
outstanding access or refresh-token lifetime plus the 60-second allowance and a
clock margin, rather than a universal 60-second wait.

## Scope Model

JWT does not define a separate permission model. The access token inherits the configured client scopes from the API-key client entry.

That means:

- API-key and JWT clients use the same scope vocabulary
- switching from API key to JWT does not require rebuilding your authorization model
- missing tool scopes still produce `403` even when authentication succeeds

For scope setup, see [Tool Scope Authorization](../../getting-started/tool-scope-authorization/).

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
