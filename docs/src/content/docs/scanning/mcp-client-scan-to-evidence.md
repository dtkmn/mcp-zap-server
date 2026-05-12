---
title: "MCP Client Scan To Evidence"
editUrl: false
description: "Run the guided MCP workflow from target selection through findings, report readback, release evidence, and customer-safe handoff."
---

Use this guide when you want to run MCP ZAP Server directly from Cursor,
Open WebUI, or another MCP client. The GitHub Action pack is useful, but it is
an integration on top of this flow, not the center of the product.

## What This Path Proves

This path takes a user from:

1. target selection
2. optional auth or API import
3. guided crawl
4. optional guided active scan
5. passive analysis drain
6. findings review
7. report generation and readback
8. internal evidence bundle
9. customer-safe handoff summary

The server currently ships as a ZAP-backed security gateway. It is not a
runtime multi-engine gateway.

## Before You Start

Use a supported MCP client:

- Cursor with `X-API-Key` configured
- Open WebUI from the bundled local stack
- any MCP client that supports streamable HTTP and custom headers

For local Compose, scan the container-reachable target URL, not the browser
preview URL:

| App | Browser preview | MCP scan target |
| --- | --- | --- |
| Juice Shop | `http://localhost:3001` | `http://juice-shop:3000` |
| Petstore | `http://localhost:3002` | `http://petstore:8080` |

## The Short Version

Ask your MCP client:

```text
Use the guided ZAP tools to scan http://juice-shop:3000.
Start with a crawl, poll until it is complete, wait for passive analysis,
show me a findings summary, generate an HTML report, read the report through
MCP, then create release evidence and a customer-safe handoff summary.
```

Tool responses include `Next Actions`. Follow those first. They are the product
lane. If a response also includes raw IDs, treat them as diagnostics, not as
the main workflow.

## Recommended Tool Sequence

### 1. Optional Target Import

Use `zap_target_import` when you have an API definition.

Use this for:

- OpenAPI
- GraphQL
- SOAP/WSDL

Skip this when the target is a normal crawlable web app and you only need page
discovery.

### 2. Optional Auth Session

For simple auth, prepare and validate a guided auth session:

1. `zap_auth_session_prepare`
2. `zap_auth_session_validate`

Use credential references such as `env:NAME` or `file:/absolute/path`. Inline
secrets should stay disabled except for local throwaway testing.

Current guided authenticated crawl and attack support prepared form-login
sessions. Bearer and API-key session preparation is useful as a contract and
validation path, but do not assume every guided scan mode consumes those
session types yet.

### 3. Start A Guided Crawl

Call `zap_crawl_start`.

Recommended parameters:

- `targetUrl`: the container-reachable target, such as `http://juice-shop:3000`
- `strategy`: `auto` for most first runs
- `authSessionId`: only when you prepared and validated a form-login session

The response returns a guided `Operation ID`. Use that operation ID with
`zap_crawl_status`. Do not switch to direct spider IDs unless you are debugging
with expert tools.

### 4. Poll Crawl Status

Call `zap_crawl_status` until the response says the crawl is complete or tells
you to continue polling.

When the crawl completes, choose one:

- continue to `zap_attack_start` if active testing is approved
- call `zap_passive_scan_wait` if crawl-only evidence is enough

Do not read findings immediately after crawl completion. Passive rules can
still be processing discovered traffic.

### 5. Optional Guided Active Scan

Call `zap_attack_start` only when the target is yours to actively test.

Recommended parameters:

- `targetUrl`: same target or target subtree
- `recurse`: `true` for a normal web target
- `policy`: optional scan policy name when you need a non-default rule set
- `authSessionId`: only when you prepared and validated a supported form-login
  session

Then poll with `zap_attack_status` until the response says the attack is
complete or tells you what to fix.

If a scan fails or is cancelled, do not proceed as if you have clean evidence.
Fix the target, auth, policy, or scan configuration first.

### 6. Wait For Passive Analysis

Call `zap_passive_scan_wait`.

This is separate because scan completion and passive-analysis completion are
not the same thing. A crawl or active scan can finish while passive rules are
still producing findings.

If the wait times out:

1. call `zap_passive_scan_status`
2. decide whether to wait again or proceed with a caveat

### 7. Review Findings

Start with `zap_findings_summary`.

Use `baseUrl` when you want target-scoped results.

Then call `zap_findings_details` when you need:

- one alert family
- plugin-specific detail
- concrete instances
- URLs, params, evidence, or attack samples

For first-pass user review, do not start with raw alert instances. Start with
summary, then drill down.

### 8. Generate And Read A Report

Call `zap_report_generate`.

Recommended parameters:

- `baseUrl`: target filter when the report should be scoped
- `format`: `html` for humans or `json` for machine processing
- `theme`: `light` unless you need dark output

The response includes the report path and next actions. Call `zap_report_read`
with that path to review the generated artifact through MCP before attaching it
to internal or customer-facing evidence.

### 9. Create Internal Evidence

Call `zap_scan_history_release_evidence`.

Use this for internal review. It can include bounded ledger details, warnings,
scan/report coverage, and evidence-window diagnostics.

Recommended parameters:

- `releaseName`: a human label such as `local-juice-shop-proof`
- `target`: the target or public target substring
- `limit`: a bounded entry count

Review warnings before you treat the evidence as ready.

### 10. Create Customer-Safe Handoff

Call `zap_scan_history_customer_handoff`.

Use this for a curated external summary. It avoids raw internal IDs, backend
references, workspace IDs, and raw metadata.

Recommended parameters:

- `handoffName`: a human label such as `juice-shop-customer-summary`
- `target`: the same public target selector used for release evidence
- `limit`: a bounded entry count

For a single-customer package, do not leave the target selector empty unless
you intentionally want every visible ledger target in the summary. Customer-safe
formatting does not automatically mean customer-scoped evidence.

Do not attach raw release evidence JSON to customer-facing material unless it
has been reviewed and explicitly approved.

## Cursor Prompt Pattern

Use prompts like this:

```text
Scan http://juice-shop:3000 using the guided ZAP tools.
Use the server's Next Actions after each tool result.
Do not use expert/direct ZAP tools unless the guided path fails.
After scanning, wait for passive analysis, summarize findings, generate an HTML
report, read the report through MCP, and create both internal release evidence
and a customer-safe handoff.
```

For an active-scan-safe target:

```text
Run a guided crawl and then a guided active scan against http://juice-shop:3000.
Poll each operation until complete, wait for passive analysis, then produce
findings, report readback, release evidence, and customer handoff.
```

For crawl-only evidence:

```text
Run only a guided crawl against http://juice-shop:3000.
When the crawl completes, wait for passive analysis, then produce findings,
report, release evidence, and customer handoff. Do not start an active scan.
```

## What The IDs Mean

| ID | Why it appears | What to do with it |
| --- | --- | --- |
| Guided operation ID | Stable handle for `zap_crawl_status`, `zap_attack_status`, and stop tools. | Use this in the guided flow. |
| ZAP scan ID | Engine diagnostic from the underlying ZAP adapter. | Keep for debugging, but do not drive the guided workflow with it. |
| Queue job ID | Durable worker job handle. | Useful for operator debugging paths; guided status wraps it. |
| Scan history entry ID | Evidence ledger record. | Use it when reading a specific ledger entry. |
| Report path | Local or mounted report artifact location. | Use it for report readback or artifact collection. |

If the tool response tells you a next action, follow that before trying to use
lower-level IDs.

## Direct Versus Queue Evidence

Direct execution proves a scan was launched and can record useful scan history.
Queue-managed execution gives stronger durable job evidence because the job has
a managed lifecycle: queued, running, succeeded, failed, or cancelled.

For casual local use, direct mode is fine.

For release or customer evidence, prefer queue-backed execution with durable
scan history. If customer handoff returns a caveat about direct-only scan proof,
do not hand-wave it away. It is telling you the evidence is useful but not as
strong as terminal queued job evidence.

Guided tools do not expose a per-call `use queue` flag. They choose direct or
queue mode from deployment topology and configuration. For release evidence,
run the Postgres/queue-backed topology and confirm the guided start response
shows `Execution Mode: queue`.

## What Ships Today

Today:

- ZAP-backed guided crawl and attack workflows
- passive scan wait and status
- guided findings summary and details
- guided report generation and readback
- scan history, release evidence, and customer handoff tools
- public extension API work for selected non-engine contracts

Not today:

- runtime multi-engine switching
- Nuclei, Semgrep, Burp, or other scanner adapters
- a generic extracted MCP gateway core repository
- marketplace-style extension discovery
- enterprise-only governance in the OSS guide

## Troubleshooting

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| Target works in browser but scan fails | You used `localhost` instead of the container target. | Use `http://juice-shop:3000` or the service name reachable from ZAP. |
| Client keeps asking for ZAP scan IDs | It is following expert guidance or old context. | Tell it to follow guided `Next Actions` and use guided operation IDs. |
| Findings look empty immediately after scan | Passive analysis has not drained. | Run `zap_passive_scan_wait`, then check findings again. |
| Handoff has caveats | Evidence window is incomplete or direct-only. | Review `zap_scan_history_release_evidence` warnings and rerun with stronger coverage if needed. |
| Authenticated scan fails | Session target, login indicators, or credential reference is wrong. | Re-run `zap_auth_session_prepare`, then `zap_auth_session_validate`. |

## Related Docs

- [MCP Client Authentication](../../getting-started/mcp-client-authentication/)
- [Scan Execution Modes](../scan-execution-modes/)
- [Passive Scan](../passive-scan/)
- [Findings And Reports](../findings-and-reports/)
- [API Schema Imports](../api-schema-imports/)
- [Scan History Ledger](../../operations/scan-history-ledger/)
- [Release Evidence Handoff Runbook](../../operations/release-evidence-handoff-runbook/)
