---
title: "MCP Access Authentication"
editUrl: false
description: "Configure API-key or JWT access from your MCP client to MCP ZAP Server."
---
This page configures access from your MCP client to MCP ZAP Server. It does
not configure ZAP to log in to the website being scanned.

| Layer | Credential | Guide |
| --- | --- | --- |
| MCP client to MCP ZAP Server | API key or JWT | This page |
| ZAP to an authorized target website | Optional website test account | [Form-Login Target Authentication](../form-login-target-authentication/) |

For the shipped HTTP/server defaults, the base runtime starts in `api-key`. Use `none` only as an explicit local dev/test override.

## Choose A Mode

For the supplied Docker Compose stack, edit `.env` and choose one of these
configurations. Start with the [first-run bootstrap](../self-serve-first-run/)
if you do not have a working local setup yet.

For the recommended API-key default:

```bash
MCP_SECURITY_MODE=api-key
MCP_API_KEY=replace-with-generated-api-key
```

For JWT in a deployment that manages token lifecycle:

```bash
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
JWT_SECRET=replace-with-generated-secret
MCP_API_KEY=replace-with-bootstrap-api-key
```

For an isolated development test only:

```bash
MCP_SECURITY_MODE=none
```

Generate credentials when needed:

```bash
# API-key and JWT bootstrap modes
openssl rand -hex 32

# JWT signing secret
openssl rand -base64 64
```

For the README's published-image stack started with
`./bin/bootstrap-local.sh --start`, recreate the server after changing the mode:

```bash
docker compose up -d --no-build --force-recreate mcp-server
```

For a source-development stack started with `./dev.sh`, include its override:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --force-recreate mcp-server
```

The Compose file explicitly passes these settings into the MCP container.
For a local `./gradlew bootRun` or `java -jar` process, export the variables or
use Spring configuration; the application does not automatically read `.env`.

Also update the credential in each MCP client and reconnect it.
If you choose JWT, configure a pre-issued bearer token in each client; see
[MCP Client Authentication](../mcp-client-authentication/).
The startup helpers' doctor checks the API-key connection, not JWT issuance or
bearer tokens. After switching to JWT, use
the token issuance and MCP checks in [JWT Quick Start](../jwt-quick-start/) to
verify readiness.

## What The Client Sends

### API Key

```http
X-API-Key: replace-with-generated-api-key
```

Recommended for the default local Compose and Cursor setup.

### JWT

```bash
TOKEN=$(curl -fsS -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"replace-with-bootstrap-api-key"}' | jq -r .accessToken)
```

The MCP client then sends:

```http
Authorization: Bearer <access-token>
```

JWT is useful only when the deployment or client handles token issuance,
expiry, refresh, and revocation. Cursor can send a pre-issued token but does not
manage that lifecycle for this server.

### None

No access credential is required. Never expose this mode to other users or
networks.

## Comparison

| Mode | Credential model | Recommended use |
| --- | --- | --- |
| `api-key` | Long-lived shared secret, rotate operationally | Default local Compose, Cursor, and small trusted deployments |
| `jwt` | Signed access and refresh tokens with expiry/revocation | Shared deployments with token lifecycle support |
| `none` | No client authentication | Isolated development tests only |

## Next Reading

- [Self-Serve First Run](../self-serve-first-run/)
- [Cursor And MCP Client Setup](../mcp-client-authentication/)
- [Optional Website Form-Login](../form-login-target-authentication/)
- [Security Modes](../../security-modes/)
- [JWT Authentication](../../security-modes/jwt-authentication/)

## Troubleshooting

### "401 Unauthorized"

- **Mode `api-key`**: Check `X-API-Key` header matches `.env`
- **Mode `jwt`**: Token might be expired, get new one

### "Security is disabled" warning

You are in `none` mode. Change to `api-key` or `jwt` before allowing any other
user or network to reach the server.

### Environment variables not loading

```bash
# Recreate the server to pick up .env changes
docker compose up -d --no-build --force-recreate mcp-server
```

Use the same Compose files that started your stack; include
`docker-compose.dev.yml` for source development as shown above. A simple
restart preserves the old container environment. For a local JVM process,
verify that the settings are exported before launching it.

## Recommendation

- **Default local Docker Compose and Cursor**: use `api-key` mode.
- **Shared deployments with token lifecycle support**: consider `jwt` mode.
- **Isolated development tests only**: use `none` only when authentication itself would block the test.

Your MCP access mode does not change whether the scan target is public or
requires its own login. Configure target authentication only when the target
actually needs it.
