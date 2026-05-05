---
title: "Observability"
editUrl: false
description: "Metrics, audit events, request correlation, and actuator endpoints."
---
MCP ZAP Server exposes a practical observability baseline:

- structured request logging with `X-Correlation-Id`
- bounded audit events
- Prometheus-ready custom metrics for request, auth, authorization, tool, queue, and protection flows

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

## Audit Event Stream

High-signal audit event types include:

- `authentication`
- `authorization`
- `tool_execution`
- `protection_rejection`

Audit data includes `correlationId`, `clientId`, and `workspaceId` so operators can pivot between request logs and audit events.

## Trace Validation

Recommended validation flow:

1. send a request with `X-Correlation-Id`
2. confirm the response echoes `X-Correlation-Id`
3. confirm error bodies include `correlationId`
4. search `request.completed` logs for that ID
5. query `/actuator/auditevents` and confirm related audit entries include the same ID

## Current Gap

This repo does not currently ship bundled Grafana dashboards or Prometheus alert rules in-tree. The metrics contract is there. The example dashboards are not.

That means the docs should tell the truth:

- you can scrape and alert on these metrics now
- you still need to build or import your own dashboards and alerts
