---
title: "Tool Scope Authorization"
editUrl: false
description: "Configure per-tool scopes for API-key and JWT clients."
---
MCP ZAP Server separates authentication from authorization:

- authentication answers who is calling
- authorization answers which MCP actions that caller may use

That distinction matters. A valid API key or JWT should not automatically grant the whole tool surface.

## Authorization Modes

Configure tool-level authorization with:

```yaml
mcp:
  server:
    security:
      authorization:
        mode: enforce
        allowWildcard: true
```

Environment variables:

```bash
MCP_SECURITY_AUTHORIZATION_MODE=enforce
MCP_SECURITY_AUTHORIZATION_ALLOW_WILDCARD=true
```

Modes:

- `off`: do not check scopes
- `warn`: log missing-scope usage but still allow it
- `enforce`: reject insufficient-scope requests with HTTP `403`

`allowWildcard=true` keeps legacy `*` clients working as a super-scope. Turn it off once your client inventory is explicit.

## Scope Flow

For API-key clients, scopes live on the configured client entry:

```yaml
mcp:
  server:
    auth:
      apiKeys:
        - clientId: ci-gate
          key: ${CI_MCP_API_KEY}
          scopes:
            - mcp:tools:list
            - zap:report:read
            - zap:alerts:read
```

In JWT mode, the access token inherits the same scopes from the source client. JWT does not magically widen permissions.

## Core Scope Families

Common scopes:

| Scope | Purpose |
| --- | --- |
| `mcp:tools:list` | Allow MCP `tools/list` discovery |
| `zap:inventory:read` | Read hosts, sites, and URLs |
| `zap:alerts:read` | Read grouped findings, raw instances, snapshots, and diffs |
| `zap:report:read` | Read guided summaries, report templates, and generated report artifacts |
| `zap:report:generate` | Generate reports |
| `zap:api:import` | Import OpenAPI, GraphQL, and SOAP/WSDL definitions |
| `zap:scan:crawl:run` | Start guided crawl flows |
| `zap:scan:attack:run` | Start guided attack flows |
| `zap:scan:active:run` | Start direct or queued active scans |
| `zap:scan:spider:run` | Start direct or queued spider scans |
| `zap:scan:ajax:run` | Start direct or queued AJAX Spider scans |
| `zap:scan:read` | Read scan status, passive backlog, queue job state, and scan history evidence |
| `zap:scan:stop` | Stop direct scans or cancel queue jobs |
| `zap:scan:queue:write` | Retry or requeue queue jobs |
| `zap:scan:policy:read` | View ZAP active-scan policies and rule state |
| `zap:scan:policy:write` | Change ZAP active-scan policy rules |
| `zap:policy:dry-run` | Preview Policy Bundle v1 decisions with `zap_policy_dry_run` |
| `zap:automation:run` | Start Automation Framework plans |
| `zap:automation:read` | Read Automation Framework status and artifacts |
| `zap:context:read` / `zap:context:write` | Read or change ZAP contexts/auth config |
| `zap:user:read` / `zap:user:write` | Read or change ZAP users |
| `zap:auth:session:write` | Prepare guided authenticated sessions |
| `zap:auth:test` | Run authenticated-user verification helpers |

## Deny Contract

When authorization is enforced and a caller lacks scope, the server returns `403` with a bounded machine-readable body:

```json
{
  "status": 403,
  "error": "insufficient_scope",
  "tool": "zap_report_read",
  "requiredScopes": ["zap:report:read"],
  "grantedScopes": ["mcp:tools:list"],
  "correlationId": "corr-123"
}
```

The response also includes:

- `WWW-Authenticate: Bearer error="insufficient_scope"...`
- the exact tool or MCP action that was denied
- the scopes the caller still needs
- `correlationId` and `requestId` for log correlation

If a public MCP tool is exposed without a scope mapping, startup validation fails instead of silently leaving an authorization gap.

## Streamable HTTP Note

If you are testing `/mcp` manually with `curl`, you must still follow the streamable MCP session flow:

1. send `initialize`
2. capture `Mcp-Session-Id`
3. include that header on later `tools/list` or `tools/call` requests

Normal MCP clients handle this automatically.

## Recommended Production Baseline

- keep `MCP_SECURITY_AUTHORIZATION_MODE=enforce`
- assign narrow scopes per client instead of sharing one broad key
- keep `mcp:tools:list` only on clients that truly need discovery
- disable wildcard scopes once migration is complete
- review scope grants when you switch from `guided` to `expert`

For surface selection, see [Tool Surfaces](./tool-surfaces/).
