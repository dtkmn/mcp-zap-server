---
title: "Tool Surfaces"
editUrl: false
description: "Understand the default guided MCP surface and the optional expert surface."
---
MCP ZAP Server ships two MCP tool surfaces:

- `guided` is the default
- `expert` adds the raw low-level tool families on top of `guided`

If you miss this distinction, the rest of the docs can look inconsistent. They are not inconsistent. The server is exposing different tool sets depending on configuration.

## Configuration

Environment variable:

```bash
MCP_SERVER_TOOLS_SURFACE=guided
```

Application config:

```yaml
mcp:
  server:
    tools:
      surface: guided
```

Valid values:

- `guided`
- `expert`

## Availability And Permissions

The selected surface determines which tools are registered at startup. A scope
grant, including `*`, does not register or enable an expert-only tool in the
guided surface. Change the surface configuration and restart the server to
change the registered tool set. Startup validation rejects registered tools
without permission mappings.

**In v0.11.1 and later**, calls to unknown tools and tools disabled
by the selected surface receive the same generic MCP error before scope checks.
This error contract is not included in v0.11.0. See
[Unknown And Disabled Tools](../tool-scope-authorization/#unknown-and-disabled-tools)
for the exact response and version requirements.

## Guided Surface

`guided` is the default because most users do not need the full ZAP control plane on day one.

Guided tools:

- `zap_target_import`
- `zap_crawl_start`
- `zap_crawl_status`
- `zap_crawl_stop`
- `zap_attack_start`
- `zap_attack_status`
- `zap_attack_stop`
- `zap_auth_session_prepare`
- `zap_auth_session_validate`
- `zap_findings_summary`
- `zap_findings_details`
- `zap_report_generate`
- `zap_report_read`
- `zap_passive_scan_status`
- `zap_passive_scan_wait`
- `zap_scan_history_list`
- `zap_scan_history_get`
- `zap_scan_history_export`
- `zap_scan_history_release_evidence`
- `zap_scan_history_customer_handoff`

Use `guided` when:

- you want a smaller, intent-first tool surface
- you are onboarding a new client quickly
- you want the server to choose direct versus queue execution for crawl and attack flows
- you want guided form-login auth bootstrap without exposing raw ZAP context/user tools
- you want scan history evidence without switching to the raw queue or report surfaces
- you do not need raw queue administration, raw auth-context setup, scan-policy tuning, or Automation Framework control

## Expert Surface

`expert` includes everything in `guided` and adds the raw tool families:

- inventory: `zap_hosts`, `zap_sites`, `zap_urls`
- direct scans: `zap_spider_*`, `zap_active_scan_*`, `zap_ajax_spider*`, `zap_client_spider_*` (Client Spider is unreleased and unavailable in `v0.12.0`)
- queue lifecycle: `zap_queue_*`, `zap_scan_job_*`
- API imports: `zap_import_*`
- findings and reports: `zap_alert_*`, `zap_findings_snapshot`, `zap_findings_diff`, `zap_view_templates`, `zap_generate_report`, `zap_report_read`
- authenticated scanning setup: `zap_context_*`, `zap_user_*`, `zap_auth_test_user`
- Automation Framework: `zap_automation_*`
- scan-policy controls: `zap_scan_policies_list`, `zap_scan_policy_view`, `zap_scan_policy_rule_set`
- runtime policy preview: `zap_policy_dry_run`

Use `expert` when:

- you need low-level authenticated scanning workflows
- you need queue job inspection, retry, or dead-letter control
- you want raw API schema import tools instead of the guided import wrapper
- you want findings snapshots, diffs, or raw report artifact reads
- you want Automation Framework plans
- you need direct control over ZAP active-scan policies

## How The Docs Map To Surfaces

These docs now call out expert-only pages explicitly.

Pages that matter to almost everyone:

- [MCP Access Authentication](../authentication-quick-start/)
- [MCP Client Authentication](../mcp-client-authentication/)
- [Optional Target Form-Login](../form-login-target-authentication/)
- [JWT Quick Start](../jwt-quick-start/)
- [Tool Scope Authorization](../tool-scope-authorization/)
- [Scan Execution Modes](../../scanning/scan-execution-modes/)
- [Client Spider (unreleased)](../../scanning/client-spider/)
- [Authenticated Scanning Reference](../../scanning/authenticated-scanning-best-practices/)
- [Passive Scan](../../scanning/passive-scan/)
- [Scan History Ledger](../../operations/scan-history-ledger/)

Pages that require `expert`:

- [AJAX Spider](../../scanning/ajax-spider/)
- [API Schema Imports](../../scanning/api-schema-imports/)
- [Scan Policy Controls](../../scanning/scan-policy-controls/)
- [Findings and Reports](../../scanning/findings-and-reports/)
- [Automation Framework](../../scanning/automation-framework/)

## Recommendation

Start with `guided` unless you already know you need the raw ZAP workflow surface.

Switch to `expert` when your workflow needs precision, replayability, or operator controls that the guided layer intentionally hides.
