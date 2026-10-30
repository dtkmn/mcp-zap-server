---
title: "Tool Scope Authorization"
editUrl: false
description: "Configure per-tool scopes for API-key and JWT clients."
---
MCP ZAP Server separates authentication from authorization:

- authentication answers who is calling
- authorization answers which MCP actions that caller may use

A valid API key or JWT identifies the caller; the configured scopes determine which exposed tools that caller can use.

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

`allowWildcard=true` lets a client with `*` pass ordinary tool-scope checks. Keep it enabled for the standard local default. Restricted deployments can turn it off after assigning explicit scopes to every intended client. Add-on updates additionally require their exact operator scope and operator configuration; a wildcard alone does not authorize them.

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

## Quick-Start Permissions

The packaged local API-key client defaults to `*` with wildcard acceptance
enabled. This permits ordinary tools in the guided catalog, including crawling, active attacks, target authentication, API imports, findings, history and reports. Retired expert tools remain unavailable even with wildcard or legacy scope grants.

`zap_addon_update` is a maintenance operation with additional checks. It requires
the explicit `zap:engine:addons:update` scope, an approved operator client,
enforced authorization, and enabled maintenance on a dedicated single-controller
engine. It remains disabled by default. See
[ZAP add-on inspection and updates](../../operations/engine-addons/).

Manual launch reconciliation is unavailable through MCP. Neither
`zap_scan_job_reconciliation_status` nor `zap_scan_job_reconcile` is registered.
Setting `ZAP_SCAN_RECONCILE_OPERATOR_CLIENT_IDS` or granting
`zap:scan:queue:reconcile` does not expose them. Uncertain launches remain held;
see [unresolved launch holds](../../operations/scan-queue-retry-policy/#operator-reconciliation).

Run only the target and workflow the user requests. MCP grants do not establish
permission to scan a system, authenticate ZAP to a website or replace target
URL/network restrictions. Keep target credentials server-side.

Bootstrap preserves existing `.env` files, keys, workspace identities and custom
client registrations. Plugin installation or reconnection does not change their
grants.

### Optional Per-Client Restrictions

For restricted or shared deployments, set explicit `scopes` on each client's
existing `mcp.server.auth.apiKeys` entry using the native configuration shown
above. Preserve its `clientId`, `workspaceId`, key and the other client entries,
including existing Compose overrides and `env_file` registrations. Apply changes
through that deployment's configuration procedure; recreating a service may be
needed to reload environment settings. Do not regenerate settings or keys to
change scopes.

Use the scope inventory below for the intended workflow. Guided active attacks
use `zap:scan:attack:run`. The former `zap:scan:active:run` grant does not authorize
`zap_attack_start` or restore retired scan-start tools. Before disabling wildcard
acceptance, give every intended client its complete explicit scope list,
including discovery and status where required.

Policy inspection uses `zap:scan:policy:read` for `zap_scan_policies_list` and
`zap_scan_policy_view`. This grant does not permit `zap_attack_start`, change
policy rules or expand target scope. `zap:scan:read` alone does not grant policy
inspection. No policy-editing tool is exposed. See
[Scan Policy Selection](../../scanning/scan-policy-controls/).

A restricted client may still discover tools it cannot call. Follow-up guidance
and discovery do not override enforced grants. If an operation returns
`insufficient_scope`, stop that operation and let the operator decide whether
to change permissions. Do not widen grants or disable enforcement automatically.

## Core Scope Families

Common scopes:

| Scope | Purpose |
| --- | --- |
| `mcp:tools:list` | Allow MCP `tools/list` discovery |
| `zap:engine:addons:read` | Inspect engine add-ons, Marketplace candidates and passive-rule readiness |
| `zap:engine:addons:update` | Request an operator-enabled update of an approved installed add-on; exact grant required |
| `zap:alerts:read` | Read grouped findings, raw instances, snapshots, and diffs |
| `zap:report:read` | Read guided summaries and generated report artifacts |
| `zap:report:generate` | Generate reports |
| `zap:api:import` | Import OpenAPI, GraphQL, and SOAP/WSDL definitions |
| `zap:scan:crawl:run` | Start guided crawl flows |
| `zap:scan:attack:run` | Start guided attack flows |
| `zap:scan:policy:read` | List configured ZAP active-scan policies and inspect their current rule settings; does not start scans or change rules |
| `zap:scan:read` | Read scan status, passive backlog, queue job state, scan history evidence, and evidence handoff summaries |
| `zap:scan:stop` | Stop or cancel a guided operation |
| `zap:auth:session:write` | Prepare guided authenticated sessions |
| `zap:auth:test` | Validate prepared guided auth sessions |

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

The ZAP `v0.14.0` integration with Gateway `0.11.0` performs active selection and
permission coverage in one `activeToolRegistry` call. It reports all missing
active-tool mappings together, and rejects null, blank, padded or duplicate
active names. It replaces the separate ZAP startup validator. This integration
is not included in ZAP `v0.13.0`; see the
[Core integration reference](https://danieltse.org/mcp-gateway-core/reference/zap-integration/) for its code map and
reproducible tests using the published libraries.

## Unknown And Disabled Tools

**Applies to MCP ZAP Server v0.11.1 and later.** The behavior below is not included
in v0.11.0. Publishing gateway-core and its WebFlux adapter `0.10.0` does not
update existing ZAP Server installations; upgrade ZAP Server itself to a version
that includes this integration.

For v0.11.1, the integration uses gateway-core and its WebFlux adapter `0.10.0`.
After authentication in API-key or JWT mode, it checks whether the requested
tool is registered and enabled before checking scopes or abuse-protection
policies.

A nonexistent tool and a retired expert tool receive the same
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
used by Spring AI, reusing descriptors from `ToolScopeRegistry`. The permission
inventory describes supported tools; the active registry includes only callbacks
registered for the current configuration. A permission mapping alone does not
expose an optional tool. No separate tool-name list is maintained. This wiring
assumes the application's current single provider;
adding another registration path or runtime tool changes requires updating the
registry assembly and its discovery-consistency tests together. The old surface selector is ignored and cannot restore retired tools.

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
- grant guided crawl/attack scopes explicitly when migrating a restricted client; legacy expert scopes are not automatically remapped

For the supported catalog and migration guidance, see [Guided Tools](../tool-surfaces/).
