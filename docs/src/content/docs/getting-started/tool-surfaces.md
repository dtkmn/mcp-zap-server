---
title: "Guided Tools"
editUrl: false
description: "Use guided MCP tools for scans, policy inspection, authentication, findings, reports, and add-on maintenance."
---
MCP ZAP Server exposes one guided tool catalog. New crawl and attack requests use the protected scan-job queue with either in-memory or PostgreSQL storage.

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

Snapshots and diffs support target-authorized baseline comparisons. See [Findings and Reports](../../scanning/findings-and-reports/).

## Operator Tools

- `zap_addon_inspect`, `zap_addon_update`, `zap_addon_update_status`

Add-on updates require explicit operator permissions and configuration. A wildcard alone does not grant an update. See [Add-on Maintenance](../../operations/engine-addons/).

Unresolved launches retain their holds and capacity; manual hold resolution is unavailable through MCP. See [Unresolved Launch Holds](../../operations/scan-queue-retry-policy/#operator-reconciliation).

## Availability and Permissions

Tool registration determines availability. Scope grants restrict calls to registered tools. Unregistered tools return the generic MCP `Unknown tool` response.

The packaged local client uses `*` for ordinary guided tools, including active attacks, authentication and imports. Run only the target and workflow the user requests. Operators can configure explicit scopes for restricted clients. See [Tool Scope Authorization](../tool-scope-authorization/).

<a id="migration-from-the-expert-interface"></a>

## Upgrading

See [Upgrading to Guided Tools](../tool-migration/) for changes to older tool names, mode settings, permissions and operation handles.

## Current Capability Limits

The guided catalog does not expose every ZAP inspection API:

| Capability | Current guided behavior |
| --- | --- |
| Report-template discovery and arbitrary template selection | `zap_report_generate` supports `html` and `json` using predefined templates. It cannot list templates or accept an arbitrary template name. Light/dark themes apply to full-session HTML; target-scoped HTML uses the engine default appearance, and JSON has no theme. |
| Complete AJAX Spider URL inventory | `zap_crawl_start` with `strategy=browser` provides status and stop controls through its Operation ID. No guided tool returns the complete AJAX Spider discovered-URL inventory; findings and scan history are not that inventory. |
| ZAP context and user inventories | `zap_auth_session_prepare` takes an operator-configured profile and target, and `zap_auth_session_validate` checks the prepared session. Neither tool lists all existing ZAP contexts or users. |
