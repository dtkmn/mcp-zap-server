---
title: "Guided Tools"
editUrl: false
description: "Use guided MCP tools for scans, policy inspection, authentication, findings, reports, and add-on maintenance."
---
MCP ZAP Server exposes one guided tool catalog. New crawl and attack requests use the protected scan-job queue with either in-memory or PostgreSQL storage. There is no selectable expert mode or agent-facing direct scan-start route.

## Scan and Authentication Tools

- `zap_target_import`
- `zap_crawl_start`, `zap_crawl_status`, `zap_crawl_stop`
- `zap_scan_policies_list`, `zap_scan_policy_view`
- `zap_attack_start`, `zap_attack_status`, `zap_attack_stop`
- `zap_auth_session_prepare`, `zap_auth_session_validate`
- `zap_passive_scan_status`, `zap_passive_scan_wait`

Use the Operation ID returned by a guided start for its status and stop calls. Admission can precede the native scan start; check status before treating it as running. See [Scan Execution](../../scanning/scan-execution-modes/).

Policy list and view tools inspect current ZAP configuration without starting a scan or changing rules. They require `zap:scan:policy:read`, separately from attack permission. See [Scan Policy Selection](../../scanning/scan-policy-controls/).

## Findings and Evidence Tools

- `zap_findings_summary`, `zap_findings_details`
- `zap_findings_snapshot`, `zap_findings_diff`
- `zap_report_generate`, `zap_report_read`, `zap_report_read_chunk`
- `zap_scan_history_list`, `zap_scan_history_get`, `zap_scan_history_export`
- `zap_scan_history_release_evidence`, `zap_scan_history_customer_handoff`

Snapshots and diffs retain their existing contracts and target authorization. They support baseline comparisons without enabling a separate tool surface. See [Findings and Reports](../../scanning/findings-and-reports/).

## Operator Tools

- `zap_addon_inspect`, `zap_addon_update`, `zap_addon_update_status`

Add-on updates require explicit operator permissions and configuration. A wildcard alone does not grant an update. See [Add-on Maintenance](../../operations/engine-addons/).

Manual launch reconciliation is unavailable through MCP. The catalog does not register `zap_scan_job_reconciliation_status` or `zap_scan_job_reconcile`; operator configuration and scope grants cannot enable them. Internal launch protection remains active, and unresolved jobs retain their holds and capacity. See [Unresolved Launch Holds](../../operations/scan-queue-retry-policy/#operator-reconciliation).

## Availability and Permissions

Tool registration determines availability. Scope grants restrict calls to registered tools; they cannot restore retired names. This also applies to wildcard grants and authorization `warn` or `off` modes. Unknown and retired tools return the same generic MCP `Unknown tool` response.

The packaged local client uses `*` for ordinary guided tools, including active attacks, authentication and imports. Run only the target and workflow the user requests. Operators can configure explicit scopes for restricted clients. See [Tool Scope Authorization](../tool-scope-authorization/).

## Migration from the Expert Interface

`MCP_SERVER_TOOLS_SURFACE` and `mcp.server.tools.surface` are retired settings. Remove them from deployment configuration. Old values, including `expert`, are ignored and cannot restore retired tools.

| Previous workflow | Current interface |
| --- | --- |
| Direct or explicit queue crawler starts | `zap_crawl_start`, then guided status/stop using its Operation ID |
| Direct or explicit queue active-scan starts | `zap_attack_start`, then guided status/stop using its Operation ID |
| Policy listing and inspection | `zap_scan_policies_list` and `zap_scan_policy_view` are guided read-only tools |
| Family-specific schema imports | `zap_target_import` |
| Raw findings summary, details or instances | `zap_findings_summary` and `zap_findings_details` |
| Snapshot and diff | Existing `zap_findings_snapshot` and `zap_findings_diff` names remain available |
| Raw report-template generation | `zap_report_generate` with its supported format/theme options |
| Raw context/user setup | Operator-managed auth profiles and guided session preparation/validation |

Raw inventory, job-ID administration/retry/dead-letter, scan-policy editing, policy-preview and Automation Framework aliases have been retired. They have no equivalent raw MCP entrypoint. The server does not silently reinterpret their old scopes as guided scan grants.

Finish or deliberately stop existing expert workflows before upgrading. A raw ZAP scan ID or queue job ID is not a guided Operation ID. Valid previously issued signed guided direct-operation handles retain status and stop support, subject to their existing signing-key lifetime. Retaining those controls does not permit new direct starts.

## Current Capability Limits

The guided catalog does not expose every ZAP inspection API:

| Capability | Current guided behavior |
| --- | --- |
| Report-template discovery and arbitrary template selection | `zap_report_generate` supports `html` and `json` using predefined templates. It cannot list templates or accept an arbitrary template name. Light/dark themes apply to full-session HTML; target-scoped HTML uses the engine default appearance, and JSON has no theme. |
| Complete AJAX Spider URL inventory | `zap_crawl_start` with `strategy=browser` provides status and stop controls through its Operation ID. No guided tool returns the complete AJAX Spider discovered-URL inventory; findings and scan history are not that inventory. |
| ZAP context and user inventories | `zap_auth_session_prepare` takes an operator-configured profile and target, and `zap_auth_session_validate` checks the prepared session. Neither tool lists all existing ZAP contexts or users. |
