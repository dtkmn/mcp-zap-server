---
title: "Abuse Protection"
editUrl: false
description: "Rate limits, workspace quotas, and overload shedding for MCP requests."
---
MCP ZAP Server includes a production-safety layer for request throttling, workspace quotas, and overload shedding.

These controls matter because one shared ZAP runtime is still easy to overwhelm through:

- accidental retry storms
- multiple clients sharing one workspace
- long-running direct scans or automation plans piling up
- queue admission that exceeds the capacity of one ZAP engine

The protection layer runs before tool execution and returns HTTP `429` when the request should be rejected.

## Per-client Request Rate Limiting

Relevant settings:

- `MCP_PROTECTION_RATE_LIMIT_ENABLED`
- `MCP_PROTECTION_RATE_LIMIT_CAPACITY`
- `MCP_PROTECTION_RATE_LIMIT_REFILL_TOKENS`
- `MCP_PROTECTION_RATE_LIMIT_REFILL_PERIOD_SECONDS`
- `MCP_PROTECTION_RATE_LIMIT_MAX_TRACKED_CLIENTS`

Default behavior:

- token-bucket style limit
- `60` requests per client per `60` seconds

## Workspace Quotas

Relevant settings:

- `MCP_PROTECTION_WORKSPACE_QUOTA_ENABLED`
- `MCP_PROTECTION_WORKSPACE_QUOTA_MAX_QUEUED_OR_RUNNING_SCAN_JOBS`
- `MCP_PROTECTION_WORKSPACE_QUOTA_MAX_DIRECT_SCANS`
- `MCP_PROTECTION_WORKSPACE_QUOTA_MAX_AUTOMATION_PLANS`

Default behavior:

- max `5` queued or running scan jobs per workspace
- max `2` direct scans per workspace
- max `2` automation plans per workspace

Workspace identity comes from the authenticated client metadata. If a client has no explicit `workspaceId`, the `clientId` becomes the workspace boundary.

## Backpressure And Overload Shedding

Relevant settings:

- `MCP_PROTECTION_BACKPRESSURE_ENABLED`
- `MCP_PROTECTION_BACKPRESSURE_MAX_TRACKED_SCAN_JOBS`
- `MCP_PROTECTION_BACKPRESSURE_MAX_RUNNING_SCAN_JOBS`
- `MCP_PROTECTION_BACKPRESSURE_MAX_DIRECT_SCANS`
- `MCP_PROTECTION_BACKPRESSURE_MAX_AUTOMATION_PLANS`

Default behavior:

- max `20` non-terminal queued jobs
- max `8` running queued jobs
- max `4` direct scans
- max `4` automation plans

## HTTP 429 Contract

Rejected requests return:

- HTTP `429 Too Many Requests`
- `Retry-After`
- JSON body with `error`, `reason`, `tool`, `clientId`, `workspaceId`, `retryAfterSeconds`, `correlationId`, and `requestId`

Example:

```json
{
  "status": 429,
  "error": "workspace_quota_exceeded",
  "reason": "workspace_direct_scans",
  "tool": "zap_active_scan_start",
  "clientId": "client-a",
  "workspaceId": "workspace-one",
  "retryAfterSeconds": 30,
  "correlationId": "corr-123"
}
```

## Current Rejection Reasons

- `rate_limited` + `client_request_rate`
- `workspace_quota_exceeded` + `workspace_scan_jobs`
- `workspace_quota_exceeded` + `workspace_direct_scans`
- `workspace_quota_exceeded` + `workspace_automation_plans`
- `overloaded` + `scan_job_backlog`
- `overloaded` + `running_scan_capacity`
- `overloaded` + `direct_scan_capacity`
- `overloaded` + `automation_capacity`

## Recommended Baseline

```bash
MCP_PROTECTION_ENABLED=true
MCP_PROTECTION_RATE_LIMIT_CAPACITY=60
MCP_PROTECTION_RATE_LIMIT_REFILL_TOKENS=60
MCP_PROTECTION_RATE_LIMIT_REFILL_PERIOD_SECONDS=60

MCP_PROTECTION_WORKSPACE_QUOTA_MAX_QUEUED_OR_RUNNING_SCAN_JOBS=5
MCP_PROTECTION_WORKSPACE_QUOTA_MAX_DIRECT_SCANS=2
MCP_PROTECTION_WORKSPACE_QUOTA_MAX_AUTOMATION_PLANS=2

MCP_PROTECTION_BACKPRESSURE_MAX_TRACKED_SCAN_JOBS=20
MCP_PROTECTION_BACKPRESSURE_MAX_RUNNING_SCAN_JOBS=8
MCP_PROTECTION_BACKPRESSURE_MAX_DIRECT_SCANS=4
MCP_PROTECTION_BACKPRESSURE_MAX_AUTOMATION_PLANS=4
```

Then tune from observed load instead of wishful thinking.
