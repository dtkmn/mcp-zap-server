---
title: "Upgrading to Guided Tools"
editUrl: false
description: "Migrate former expert tools, permissions and operation handles to the supported guided workflow."
---
Use this guide when upgrading an installation or client that used the former expert interface. For a new installation, start with the [guided tool catalog](../tool-surfaces/) and [scan-to-evidence workflow](../../scanning/mcp-client-scan-to-evidence/).

## Replace Former Tool Calls

New guided crawl and attack requests always use the protected scan-job queue, with either in-memory or PostgreSQL storage.

| Previous workflow | Current interface |
| --- | --- |
| Direct or explicit queue crawler starts | `zap_crawl_start`, then guided status/stop using its Operation ID |
| Direct or explicit queue active-scan starts | `zap_attack_start`, then guided status/stop using its Operation ID |
| Policy listing and inspection | `zap_scan_policies_list` and `zap_scan_policy_view`, requiring `zap:scan:policy:read` |
| Family-specific schema imports | `zap_target_import` |
| Raw findings summary, details or instances | `zap_findings_summary` and `zap_findings_details` |
| Snapshot and diff | Existing `zap_findings_snapshot` and `zap_findings_diff` names remain available |
| Raw report-template generation | `zap_report_generate` with its supported format/theme options |
| Raw context/user setup | Operator-managed auth profiles and guided session preparation/validation |

Raw inventory, job-ID administration/retry/dead-letter, scan-policy editing and policy-preview aliases have no equivalent raw MCP entrypoint. The guided catalog also has specific [inspection limits](../tool-surfaces/#current-capability-limits), including report templates, AJAX URL inventory and context/user inventories.

## Remove Obsolete Mode Settings

Remove `MCP_SERVER_TOOLS_SURFACE` and `mcp.server.tools.surface` from deployment configuration. Their old values, including `expert`, are ignored. They cannot select another catalog or enable direct scan starts.

## Update Client Permissions

Scope grants apply only to registered tools. Update restricted clients to grant the current actions they need:

| Action | Required scope |
| --- | --- |
| Discover tools | `mcp:tools:list` |
| Start a guided crawl | `zap:scan:crawl:run` |
| Start a guided active scan | `zap:scan:attack:run` |
| Read guided scan status | `zap:scan:read` |
| Stop a guided scan | `zap:scan:stop` |
| List or inspect active-scan policies | `zap:scan:policy:read` |

Legacy expert scopes are not automatically remapped to guided scan grants. In particular, `zap:scan:active:run` does not authorize `zap_attack_start`. Policy inspection does not grant permission to run attacks or edit rules. See [Tool Scope Authorization](../tool-scope-authorization/) for authentication, import, findings, report and maintenance scopes.

Wildcard grants and authorization `warn` or `off` modes cannot restore an unregistered tool. Unknown and retired names receive the same generic MCP `Unknown tool` response. Add-on maintenance retains its separate explicit operator checks.

## Existing Operation Handles

Finish or deliberately stop existing expert workflows before upgrading. Raw ZAP scan IDs and queue job IDs are not guided Operation IDs and cannot be substituted for them.

Valid previously issued signed guided direct-operation handles retain status and stop support, subject to their existing signing-key lifetime. Preserve the configured signing key when handles must remain valid across MCP replacement. These compatibility controls do not permit new direct starts.

## Unresolved Launches and Reconciliation

Manual launch reconciliation is unavailable through MCP. Neither `zap_scan_job_reconciliation_status` nor `zap_scan_job_reconcile` is registered, and granting `zap:scan:queue:reconcile` cannot expose them.

Remove the obsolete `ZAP_SCAN_RECONCILE_OPERATOR_CLIENT_IDS` environment variable, `zap.scan.jobs.launch-safety.operator-client-ids` property and Helm `mcp.launchSafety.operatorClientIds` value from existing configurations. Keep the launch-journal and persistence settings; they still protect uncertain starts. The separate add-on maintenance operator settings remain in use.

Remove the two tool names from runtime policy-bundle rules as well: policy validation rejects names outside the current catalog. Keep all other access restrictions; unavailable tools are rejected before policy evaluation.

Internal launch protection remains active. Uncertain jobs keep their holds and capacity, and an unresolved start is not automatically submitted again. A trusted late receipt can still resolve its exact outstanding attempt.

There is no supported MCP command to attach a native scan ID, manually clear a launch receipt or restore tracking after controller replacement. Preserve the job database and journal; do not delete recovery state or submit a replacement scan to bypass an unknown outcome. See [Queue Retry and Launch Recovery](../../operations/scan-queue-retry-policy/#operator-reconciliation) for status interpretation, restart limitations and existing cleanup jobs.

## Automation Framework

The former MCP Automation Framework plan-run, plan-status and plan-artifact tools are retired. Plan IDs and plan paths cannot be converted into guided Operation IDs.

For agent-driven scanning, sequence the supported tools: prepare authentication or import an API definition when required, start an authorized crawl or attack, monitor its Operation ID, wait for passive analysis, then read findings and retrieve reports. See [Scan Execution](../../scanning/scan-execution-modes/).

If a workflow requires native YAML plans, use a separately managed ZAP deployment and [ZAP's Automation Framework documentation](https://www.zaproxy.org/docs/automate/automation-framework/). Its status, cancellation, credentials and artifacts belong to that workflow; the MCP scan queue does not track it. Finish or deliberately stop old plans through the existing operator workflow before upgrading.

Automation workspace settings and optional native examples do not expose plan execution through MCP. Keep the configured shared-storage, containment and directory-separation safeguards in place. See the [Helm workspace guidance](https://github.com/dtkmn/mcp-zap-server/blob/main/helm/mcp-zap-server/README.md#shared-report-storage) or [EC2 deployment guide](../../operations/aws-ec2-compose/).

## Release-Specific Upgrade Notes

This page describes the current guided interface. Consult the [release notes archive](https://github.com/dtkmn/mcp-zap-server/blob/main/docs/releases/README.md) for the behavior and upgrade requirements of an older release, and deploy matching source, chart and image versions.
