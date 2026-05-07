---
title: "Scan History Ledger"
editUrl: false
description: "Queryable scan, queue, and report evidence for release handoff and operations."
---

# Scan History Ledger

The scan history ledger is the shared evidence trail for the gateway. It answers
the operator question: what scan or report evidence exists, when was it created,
what target did it relate to, and which backend reference can be used to trace it?

## What Is Recorded

The shared core records three evidence shapes:

| Evidence type | Source | Notes |
| --- | --- | --- |
| `scan_job` | Durable queue state from `scan_jobs` | Includes queued, running, succeeded, failed, and cancelled queue jobs. |
| `scan_run` | Direct scan start events | Covers direct active scan, spider, authenticated variants, and AJAX Spider starts. |
| `report_artifact` | Generated report artifacts | Captures report path, media type, target scope, client, and workspace. |

This does not change the normal MCP client setup. Clients still call `/mcp` and
the existing scan/report tools as before. The new query tools expose the ledger:

- `zap_scan_history_list`
- `zap_scan_history_get`
- `zap_scan_history_export`
- `zap_scan_history_release_evidence`
- `zap_scan_history_customer_handoff`

All five use the existing `zap:scan:read` scope.

## Storage Backends

By default the ledger follows the scan-job store backend configured for this
deployment. Local development stays in memory. Multi-replica or release-evidence
deployments should use Postgres.

```yaml
zap:
  scan:
    jobs:
      store:
        backend: postgres
        postgres:
          url: jdbc:postgresql://postgres:5432/mcp_zap
          username: mcp
          password: ${POSTGRES_PASSWORD}
    history:
      backend: postgres
      retention-days: 180
      max-list-entries: 50
      max-export-entries: 500
      postgres:
        table-name: scan_history_entries
```

Environment variables:

```bash
ZAP_SCAN_HISTORY_BACKEND=postgres
ZAP_SCAN_HISTORY_RETENTION_DAYS=180
ZAP_SCAN_HISTORY_MAX_LIST_ENTRIES=50
ZAP_SCAN_HISTORY_MAX_EXPORT_ENTRIES=500
ZAP_SCAN_HISTORY_POSTGRES_URL=jdbc:postgresql://postgres:5432/mcp_zap
ZAP_SCAN_HISTORY_POSTGRES_USERNAME=mcp
ZAP_SCAN_HISTORY_POSTGRES_PASSWORD=...
ZAP_SCAN_HISTORY_POSTGRES_TABLE_NAME=scan_history_entries
ZAP_SCAN_HISTORY_POSTGRES_FAIL_FAST=true
```

If `ZAP_SCAN_HISTORY_POSTGRES_URL` is blank, the ledger falls back to the scan-job
Postgres URL when the scan-job backend is Postgres.

## Retention And Export

`retention-days` controls how long dedicated ledger entries are kept. A value of
`0` disables automatic pruning. Queue-backed `scan_job` entries are projected
from the durable queue table, so queue retention is controlled by the queue state
policy and database maintenance around `scan_jobs`.

Use `zap_scan_history_export` to produce a bounded JSON evidence snapshot for:

- release sign-off
- pilot handoff
- incident review
- upgrade and rollback comparison

Use `zap_scan_history_release_evidence` when the reviewer needs a handoff-ready
internal bundle rather than a raw ledger slice. It includes the bounded entries
plus:

- release or pilot label
- summary counts by evidence type and status
- target coverage
- first/last recorded timestamps
- warnings when scan evidence, report artifacts, non-terminal included queue
  jobs, or export-limit headroom need reviewer attention

Use `zap_scan_history_customer_handoff` for the external package. It generates a
customer-safe Markdown summary that omits raw ledger IDs, backend references,
workspace/client IDs, artifact paths, idempotency keys, and raw metadata. Attach
reviewed report files separately; do not hand raw ledger JSON to a customer
unless it has been explicitly reviewed and redacted.

Do not treat the export as a full compliance data warehouse. It is a compact
gateway evidence snapshot. Extensions can add longer retention, tenant-specific
exports, reporting overlays, and review workflows on top of the same shared
ledger contracts.

## Access Boundary

The shared core exposes `ScanHistoryAccessBoundary` for extensions that need to
filter history visibility. Queue-job history also respects the existing
`ScanJobAccessBoundary`. This lets overlays add tenant-aware visibility without
forking the shared ledger implementation.

## Operator Checks

Before relying on the ledger for release evidence:

1. Run Flyway migrations so `scan_history_entries` exists.
2. Confirm `zap_scan_history_list` returns queue jobs after a queued scan.
3. Confirm direct scan starts appear as `scan_run` entries.
4. Generate a report and confirm a `report_artifact` entry appears.
5. Export `zap_scan_history_release_evidence` and attach it to the release or
   pilot handoff record.
6. Generate `zap_scan_history_customer_handoff` for the external package when a
   customer, design partner, or pilot reviewer needs a readable summary.

For the exact handoff sequence and sign-off template, use
[Release Evidence Handoff Runbook](./release-evidence-handoff-runbook/).
