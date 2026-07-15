---
title: "MCP Access Authentication"
editUrl: false
description: "Choose how Cursor, Open WebUI, or another MCP client authenticates to MCP ZAP Server."
---
This page configures access from Cursor, Open WebUI, or another MCP client to
MCP ZAP Server. It does not configure ZAP to log in to the website being
scanned.

| Layer | Credential | Guide |
| --- | --- | --- |
| MCP client to MCP ZAP Server | API key or JWT | This page |
| ZAP to an authorized target website | Optional website test account | [Form-Login Target Authentication](../form-login-target-authentication/) |

For the shipped HTTP/server defaults, the base runtime starts in `api-key`. Use `none` only as an explicit local dev/test override.

## Choose A Mode

Edit `.env` and choose one of these configurations.

For the recommended API-key default:

```bash
MCP_SECURITY_MODE=api-key
MCP_API_KEY=replace-with-generated-api-key
```

For JWT in a deployment that manages token lifecycle:

```bash
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
JWT_SECRET=your-256-bit-secret-minimum-32-chars
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

Recreate the server after changing the mode:

```bash
docker compose up -d --force-recreate mcp-server open-webui
```

Also update the credential in Cursor or any other client. Open WebUI receives
the API key when its container is created, which is why it is recreated here.
If you choose JWT, configure a pre-issued bearer token in each client; see
[MCP Client Authentication](../mcp-client-authentication/).

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
# Recreate containers to pick up .env changes
docker compose up -d --force-recreate mcp-server open-webui
```

## Recommendation

- **Default local Docker Compose and Cursor**: use `api-key` mode.
- **Shared deployments with token lifecycle support**: consider `jwt` mode.
- **Isolated development tests only**: use `none` only when authentication itself would block the test.

Your MCP access mode does not change whether the scan target is public or
requires its own login. Configure target authentication only when the target
actually needs it.
