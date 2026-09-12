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
| `zap:scan:read` | Read scan status, passive backlog, queue job state, scan history evidence, and evidence handoff summaries |
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

For a registered, enabled tool, when authorization is enforced and a caller lacks scope, the server returns `403` with a bounded machine-readable body:

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

## Unknown And Disabled Tools

The server uses gateway-core and its WebFlux adapter `0.10.0`. After authentication
in API-key or JWT mode, it checks whether the requested tool is registered and
enabled before checking scopes or abuse-protection policies.

A nonexistent tool and a tool disabled by the selected surface receive the same
HTTP `200` JSON response, without a `WWW-Authenticate` challenge:

```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "error": {"code": -32602, "message": "Unknown tool"}
}
```

The original string or integer request ID is preserved. The error does not reveal
the requested name, whether the tool is disabled, or its required scopes. Granting
the scope or a wildcard does not enable a disabled tool. This availability check
also applies in authorization `warn`/`off` and security `none` modes; it does not
turn authentication back on when security is disabled.

The adapter's immutable registry is derived from the same `ToolCallbackProvider`
used by Spring AI, reusing descriptors from `ToolScopeRegistry`. The full
permission inventory may include disabled tools, but discovery and the adapter's
active registry contain only registered tools. No separate tool-name list is
maintained. This wiring assumes the application's current single provider;
adding another registration path or runtime tool changes requires updating the
registry assembly and its discovery-consistency tests together. Surface changes
take effect when the application restarts.

Tool calls require a request ID: id-less `tools/call` messages receive HTTP `202`
with no body and do not execute. Explicit null, fractional, or non-string/non-integer
IDs receive HTTP `400` with JSON-RPC `-32600` (`Invalid Request`). Ordinary
notifications retain their existing protocol handling.

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

For surface selection, see [Tool Surfaces](../tool-surfaces/).
