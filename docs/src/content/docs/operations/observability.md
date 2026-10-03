---
title: "Observability"
editUrl: false
description: "Metrics, audit events, request correlation, and actuator endpoints."
---
MCP ZAP Server exposes a practical observability baseline:

- structured request logging with `X-Correlation-Id`
- bounded audit events
- Prometheus-ready custom metrics for request, auth, authorization, tool, queue, and protection flows
- bundled Prometheus alert rules and a starter Grafana dashboard

## Exposed Actuator Endpoints

- `/actuator/health`
- `/actuator/info`
- `/actuator/metrics`
- `/actuator/prometheus`
- `/actuator/auditevents`

`health` and `info` are lightweight checks. The richer endpoints should stay behind private or authenticated access paths.

## High-signal Custom Metrics

| Metric | Type | Purpose |
| --- | --- | --- |
| `mcp.zap.http.requests` | Timer | End-to-end HTTP timing |
| `mcp.zap.auth.events` | Counter | Auth success and failure reasons |
| `mcp.zap.authorization.decisions` | Counter | Scope authorization allow, deny, and warn decisions |
| `mcp.zap.tool.executions` | Timer | MCP tool duration and outcome |
| `mcp.zap.audit.events` | Counter | Audit-stream emission volume |
| `mcp.zap.protection.rejections` | Counter | Rate-limit, quota, and overload rejections |
| `mcp.zap.invalid_mcp_requests` | Counter | Malformed MCP request rejections by reason |
| `mcp.zap.adapter.rejections` | Counter | Gateway 0.11.0 integration in ZAP v0.14.0: typed adapter rejections by bounded reason code |
| `mcp.protection.rate_limited` | Counter | Legacy/shared rate-limit rejection count |
| `mcp.protection.workspace_quota_rejections` | Counter | Legacy/shared workspace-quota rejection count |
| `mcp.protection.backpressure_rejections` | Counter | Legacy/shared overload rejection count |
| `mcp.zap.queue.jobs` | Gauge | Durable queue depth by status |
| `mcp.zap.queue.claims` | Gauge | Active versus expired job claims |
| `mcp.zap.queue.claim.events` | Counter | Claim, renewal, conflict, and recovery events |
| `mcp.zap.queue.leadership.is_leader` | Gauge | Optional coordinator leadership state |
| `mcp.zap.queue.leadership.transitions` | Counter | Coordinator acquisition and loss |
| `mcp.zap.queue.leadership.failures` | Counter | Coordinator acquire and heartbeat failures |
| `mcp.zap.operations.active` | Gauge | In-memory direct-scan and automation activity |

<a id="development-http-metric-labels"></a>

### HTTP metric labels in v0.14.0

The following changes apply to `v0.14.0` and are not included in `v0.13.0`.
Check [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) for
availability:

- The `path` tag uses the matched route pattern, including configured MCP
  endpoints and templates such as `/entries/{id}`. Requests rejected before
  routing, and unmatched requests, use `/unmatched`. Raw paths remain in the
  structured completion log rather than becoming metric labels.
- Standard HTTP methods retain their lowercase labels. Extension methods use
  `other`; unavailable methods and status codes use `unknown`.
- The custom HTTP timer admits at most 1,016 distinct tag combinations. Further
  combinations use at most eight overflow series with `path=/overflow`,
  `method=other`, and `status=unknown`, retaining the original `outcome` and
  `authenticated` values. This keeps the total at or below 1,024 series even
  during concurrent registration. Overflow requests still contribute their
  count and duration; existing series continue to retain their detailed labels.

## Audit Event Stream

High-signal audit event types include:

- `authentication`
- `authorization`
- `policy_decision`
- `tool_execution`
- `protection_rejection`

Audit events include available identity and correlation fields so operators can
pivot between request logs and audit events. Authentication, policy and tool
execution records retain their application-owned details. Diagnostic records do
not establish a caller or workspace identity.

<a id="development-gateway-0110-audit-schema"></a>

### Gateway 0.11.0 audit schema in v0.14.0

ZAP `v0.14.0` uses published Gateway `0.11.0` libraries. This integration is not
included in ZAP `v0.13.0`, which uses Gateway `0.10.0`.
Its WebFlux callbacks publish one shared audit event per signal, after updating
ZAP's existing domain metrics. The schema below applies to `v0.14.0`:

| Type | Outcome | Data |
| --- | --- | --- |
| `authorization` | `allowed`, `denied`, or `warn` | `action`, `reason`, `requiredScopes`, `grantedScopes`; available `workspaceId` and `correlationId` |
| `protection_rejection` | `rejected` | `tool`, `errorCode`, `reason`, `retryAfterSeconds`, `workspaceId`; available context `correlationId` |
| `invalid_mcp_request` | `rejected` | Available `reason`, server HTTP `requestId`, and `correlationId` |
| `adapter_rejection` | `rejected` | Typed `reason` code, server HTTP `requestId`, and available `correlationId` |

The sink adds `data.outcome` and stores the event principal separately. It uses
`anonymous` when the shared diagnostic event has no principal; it does not
infer a workspace, client or tool from those diagnostics. `requestId` identifies
the HTTP request, not its JSON-RPC id. Audit storage is the configured bounded
in-memory Actuator repository plus structured logs; the bridge adds no durable
storage or delivery guarantee.

When migrating queries from the released `v0.13.0` schema:

- Read the event principal instead of `data.clientId` for governance records.
- Authorization now includes `reason` and both scope lists, including empty lists.
  Supplied action/workspace values retain their spelling in audit details;
  metric tags continue to use ZAP's existing normalization.
- Filter protection audits by `outcome=rejected` and use `data.errorCode` to
  distinguish `rate_limited`, quota or backpressure decisions. The old outcome
  was the error code. `toolFamily` remains a protection metric tag but is absent
  from the shared audit record.
- Invalid-request outcomes become `rejected`, with the diagnostic reason in
  `data.reason`; diagnostics omit the previous inferred default workspace.

An allowed authorization followed by a protection rejection produces two
different governance events. These events occur before execution and do not prove
tool completion. A separate `tool_execution` event records actual completion.
Audit-sink exceptions still propagate through the filter and can prevent the
normal response and downstream execution. See the
[Core integration reference](https://danieltse.org/mcp-gateway-core/reference/zap-integration/)
for the configured server exercise and boundaries.

## Trace Validation

Recommended validation flow:

1. Send a request with a safe `X-Correlation-Id`, such as `trace-check-1`.
2. Read the response's `X-Correlation-Id` and use that returned value for tracing;
   missing or unsafe caller values may be replaced.
3. Search `request.completed` logs for that ID.
4. When auditing is enabled and the request emits an audit event, query
   `/actuator/auditevents` and match `data.correlationId` in retained entries.

Error-body fields depend on the response path:

| Response | Body tracing fields |
| --- | --- |
| HTTP governance errors: permission denial (`403`), protection rejection (`429`), invalid message shape (`400`), or body-size limit (`413`) | Normally include `correlationId` and the server HTTP `requestId`. |
| Gateway JSON-RPC errors, such as an unknown or disabled tool (`-32602`) | Contain only `jsonrpc`, JSON-RPC `id`, and `error.code` / `error.message`; trace through the response header. |
| ZAP `v0.14.0` with Gateway `0.11.0`: invalid execution context (`500`) | Contains only `{"error":"invalid_execution_context"}`; trace through the response header. |

The JSON-RPC `id` and server HTTP `requestId` are separate identifiers. Use the
response correlation header across these paths, including errors whose bodies
omit `correlationId`.

## Bundled Assets

The repo ships starter observability assets:

- `ops/observability/grafana-dashboard.json`
- `ops/observability/prometheus-alerts.yml`

The Grafana dashboard covers request rate, authentication events, authorization decisions, tool throughput and duration, protection rejections, queue jobs, and audit event volume.

The Prometheus alert file includes warning rules for:

- elevated authentication failures
- sustained protection rejections
- queued scan backlog
- repeated tool execution failures

Import these as release baselines, then tune thresholds from real traffic. Do not treat the starter dashboard as an SLO model; it is there to stop operators from starting with a blank screen.

## Policy Decision Evidence

Policy preview, runtime dry-run, and runtime enforcement emit `policy_decision` audit events with the same correlation ID as the tool request when one is available.

Expected outcomes include:

- `allow`
- `deny`
- `dry_run_allow`
- `dry_run_deny`
- `invalid`

During rollout, watch for unexpected `dry_run_deny` and `invalid` events before switching `MCP_POLICY_MODE` to `enforce`.
