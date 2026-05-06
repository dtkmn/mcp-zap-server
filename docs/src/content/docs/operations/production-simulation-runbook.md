---
title: "Production Simulation Runbook"
editUrl: false
description: "Run a staged proof before recommending a deployment as production-ready."
---

Run this before recommending a deployment as production-ready for a client or
design partner.

## Goal

Prove that the current product can be operated safely and repeatably inside its
real support boundary:

- one trust boundary per deployment unit
- one private ZAP runtime per trust boundary
- authenticated MCP access
- durable shared state where HA MCP replicas are used

## Preconditions

- [Production Readiness Checklist](./production-checklist/) is materially
  complete.
- Runtime secrets come from a secret manager or Kubernetes Secret references.
- ZAP remains private-only.
- MCP auth and scope enforcement are enabled.
- Durable Postgres-backed state is configured for HA MCP deployments.
- Observability paths, log retention, and alert routing are live.

## Required Scenario Flow

### 1. Baseline Rollout Validation

- render the target Helm overlay or deployment config
- verify migrations are applied successfully
- deploy or roll the staged environment
- confirm health for MCP, ZAP, database connectivity, and ingress

### 2. Auth And Tool Contract Validation

- confirm the MCP endpoint rejects unauthenticated requests
- confirm the intended auth mode works
- confirm `tools/list` succeeds for the intended client identity
- confirm scope enforcement behaves as expected for at least one allowed and one
  denied action

### 3. Pilot Proof Scenario: Governed Authenticated Scan

Use this scenario when deciding whether the gateway is pilot-ready for a design
partner. This is the minimum realistic path: auth setup, auth validation, policy
decision, scan execution, passive scan wait, findings, and report evidence in
one chain.

Run sequence:

1. Call `zap_auth_session_prepare` with `authKind=form`, the staging
   `targetUrl`, `loginUrl`, `username`, field names, login indicators, and a
   credential reference.
2. Record the response `correlationId`, `Session ID`, `Context ID`, `User ID`,
   provider, and `Engine Binding`.
3. Call `zap_auth_session_validate` with that session ID.
4. Confirm `Valid: true`, `Outcome: authenticated`, and
   `likelyAuthenticated=true`.
5. Call `zap_crawl_start` with the same target, `strategy=http`, and the
   `authSessionId`.
6. Record the crawl `correlationId`, guided `Operation ID`, execution mode, and
   backend scan/job ID if present.
7. Poll `zap_crawl_status` until complete enough for the pilot target.
8. Call `zap_passive_scan_wait` and record the passive-scan outcome.
9. Call `zap_attack_start` with the same target and `authSessionId`.
10. Record the attack `correlationId`, guided `Operation ID`, execution mode,
    and backend scan/job ID if present.
11. Poll `zap_attack_status` until complete enough for the pilot target.
12. Call `zap_passive_scan_wait` again before reading evidence.
13. Call `zap_findings_summary`, then `zap_findings_details` for one material
    alert family if findings exist.
14. Call `zap_report_generate` and record the report artifact path.
15. Call `zap_scan_history_release_evidence` with the pilot label and target
    filter, then review the bundle warnings before handoff.
16. Call `zap_scan_history_customer_handoff` with the same label and target
    filter, then attach that curated summary to the external pilot package.
17. Query audit events or the configured audit sink for matching
    `policy_decision` events on the crawl/attack/report correlation IDs.

Expected operator evidence:

| Evidence | Required proof |
| --- | --- |
| Correlation IDs | One for auth prepare, auth validate, crawl, attack, passive wait, findings, and report generation. |
| Auth evidence | Session ID, context ID, user ID, provider, engine binding, validation outcome, and diagnostics. |
| Policy evidence | `policy_decision` audit event with bounded tags and the same correlation ID as the tool call. |
| Scan evidence | Guided operation ID plus backend scan ID or queued job ID where available. |
| Passive evidence | Passive scan wait result after crawl and after attack. |
| Findings evidence | Findings summary and one details drilldown when findings exist. |
| Report evidence | Generated report artifact path and storage confirmation where available. |
| Release evidence | `zap_scan_history_release_evidence` internal bundle with summary counts, targets, and reviewed warnings. |
| Customer handoff | `zap_scan_history_customer_handoff` curated summary with no raw internal ledger fields. |
| Secret handling | No raw secret in client response, logs, audit event, report, ticket, or screenshot. |

Concrete blockers must become follow-up issues before sign-off. Common examples:

- authenticated browser/AJAX crawl is required
- bearer/API-key header injection is required for guided execution
- policy mode cannot emit an audit decision for the pilot tool call
- scan job or operation IDs cannot be correlated with logs
- report artifacts cannot be mapped to durable storage
- the target requires MFA, SSO, CAPTCHA, or another unsupported auth adapter

Do not expand this scenario mid-run to hide a gap. If the path fails on a real
product limitation, write the follow-up issue and mark the pilot proof as failed
or passed with an explicit caveat.

### 4. HA Recovery Check

If the deployment uses multiple MCP replicas:

- terminate or restart one MCP replica while a claimed queued job is active
- confirm another replica resumes polling after lease expiry
- confirm no duplicate scan start occurs

If the deployment is single-replica, record that HA recovery is out of scope for
this run.

## Minimum Evidence To Capture

- deployment render or rollout command output
- health checks for MCP and ZAP
- one authenticated MCP interaction trace
- one policy decision audit event for the governed scan path
- one guided operation ID plus backend scan or job ID where available
- one passive-scan wait result after scan traffic
- one findings summary result
- one generated report artifact reference
- one release/pilot evidence bundle from `zap_scan_history_release_evidence`
- one customer-safe handoff summary from `zap_scan_history_customer_handoff`
- relevant correlation IDs
- HA failover evidence where applicable
- all gaps written down as explicit follow-up issues

## Exit Criteria

- the supported scan path succeeded end-to-end
- the governed authenticated pilot proof either passed or produced explicit
  follow-up issues for each blocker
- auth and authorization behaved as expected
- policy decisions were observable and matched the configured mode
- no duplicate scan start occurred during failover testing
- unresolved gaps are explicit and accepted, not hand-waved

## Suggested Sign-Off Comment

```text
Staged production simulation completed on YYYY-MM-DD.

Environment:
- cloud/provider or staged environment name
- auth mode
- queue/state backend
- replica shape

What was exercised:
- rollout and health validation
- authenticated MCP session and tools/list
- governed authenticated pilot proof: auth prepare/validate -> policy decision -> crawl/attack -> passive wait -> findings -> report
- HA recovery check (or explicitly out of scope)

Evidence:
- links or artifact references
- representative correlation IDs
- policy_decision audit event reference
- guided operation ID and backend scan/job ID
- report artifact location
- release evidence bundle summary and warnings

Result:
- pass / pass with accepted caveats / fail

Principal engineering sign-off:
- approved / not approved
- name
- date
```

## Related Docs

- [Production Readiness Checklist](./production-checklist/)
- [Release Evidence Handoff Runbook](./release-evidence-handoff-runbook/)
- [Queue Coordinator and Worker Claims](./queue-coordinator-leader-election/)
