# Quick Start Security Guide

This is the security companion to [Self-Serve First Run](docs/getting-started/SELF_SERVE_FIRST_RUN.md).
Use that guide for installation and recovery; review the controls below before
changing who can access the server or what ZAP can reach.

## Fastest Local Path

Follow [Self-Serve First Run](docs/getting-started/SELF_SERVE_FIRST_RUN.md) to
start the local stack, connect a client, and generate a demo report. The startup
command checks container health and authenticated MCP access before reporting
ready.

## Local Security Reality

The default local stack uses API-key authentication and publishes host ports
on loopback. Bootstrap generates separate MCP and ZAP keys and enables
localhost/private-network targets for the bundled demos. Those target settings
are for an isolated lab and need review before shared deployment.

- Keep `.env`, keys, bearer tokens, and client configs containing credentials
  out of version control and shared logs.
- Keep loopback binding unless you deliberately expose the stack behind
  trusted network controls. Authentication alone does not provide HTTPS.
- Only scan systems you own or are explicitly authorized to test. An allowed
  URL is a network-policy decision, not evidence of permission to scan it.
- MCP API keys or JWT authenticate the client to this server. Optional target
  login credentials authenticate ZAP to a website; keep those credentials
  server-side and out of MCP prompts and client settings.

## Client Setup

Use the [MCP client guide](docs/src/content/docs/getting-started/mcp-client-authentication.md)
for Codex, Cursor, supported headers, and connection troubleshooting. API-key
mode uses `X-API-Key`; `Authorization: Bearer` requires an issued JWT access token.
For websites requiring login, use
[Form-Login Target Authentication](docs/src/content/docs/getting-started/form-login-target-authentication.md).

## Manual MCP Check

Use the doctor's connection checks from the first-run guide. For raw HTTP
diagnostics, follow the [manual MCP session example](docs/src/content/docs/getting-started/mcp-client-authentication.md#test-the-endpoint-manually);
a bare GET to `/mcp` does not verify protocol initialization or tool calls.

## JWT Quick Check

Use JWT when the deployment manages issuance, expiry, refresh, and revocation.
Follow [JWT Quick Start](docs/src/content/docs/getting-started/jwt-quick-start.md)
and the [JWT Authentication guide](docs/src/content/docs/security-modes/jwt-authentication.md).
The startup helpers' doctor verifies the API-key path; it does not validate
JWT issuance or bearer-token readiness.

## Recommended Local Settings

| Environment | Required review |
| --- | --- |
| Isolated local lab | Keep API-key auth and loopback binding; allow private targets only for the intended local test scope. |
| Shared or cloud deployment | Keep authentication enabled, protect ingress with TLS and network controls, restrict target egress and `ZAP_URL_WHITELIST`, and review workspace access and scan isolation. |

Use `none` only for explicit isolated development tests. Shared deployments
need the [Production Readiness Checklist](docs/src/content/docs/operations/production-checklist.md);
switching to JWT alone does not make a shared ZAP engine safe for unrelated users.

## Apply Configuration Changes

For the published-image stack, recreate the MCP service after changing its
authentication settings:

```bash
docker compose up -d --no-build --force-recreate mcp-server
```

Use the same Compose files that started the stack. Source development uses
`docker-compose.dev.yml`; follow the
[MCP authentication configuration guide](docs/src/content/docs/getting-started/authentication-quick-start.md#choose-a-mode)
for that command and client credential updates. A container restart alone does
not reload its environment.

## Common Mistakes

For authentication, session, or target-host failures, use the
[client troubleshooting guide](docs/src/content/docs/getting-started/mcp-client-authentication.md#troubleshooting).
For container startup or workspace failures, use the
[first-run recovery guide](docs/getting-started/SELF_SERVE_FIRST_RUN.md).
Do not disable authentication or broaden target access merely to suppress an error.

## Read Next

- [Security Modes](docs/src/content/docs/security-modes/index.md)
- [Production Readiness Checklist](docs/src/content/docs/operations/production-checklist.md)
- [Security Policy](SECURITY.md)
