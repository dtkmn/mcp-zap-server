# Production Simulation Runbook

Run this before recommending a deployment as production-ready for a client or design partner.

## Goal

Prove that the current product can be operated safely and repeatably inside its real support boundary.

The deployment baseline is:

- one trust boundary per deployment unit
- one private ZAP runtime per trust boundary
- authenticated MCP access
- durable shared state where HA MCP replicas are used

## Preconditions

Before you start, the environment should already satisfy the production baseline:

- [Production Readiness Checklist](https://danieltse.org/mcp-zap-server/operations/production-checklist/) is materially complete
- pinned images or digests are selected
- runtime secrets come from a secret manager or Kubernetes Secret references
- ZAP remains private-only
- MCP auth and scope enforcement are enabled
- durable Postgres-backed state is configured for HA MCP deployments
- Postgres-backed scan history is enabled when pilot evidence must survive
  restart, failover, or handoff; queued evidence also requires durable scan-job state
- observability paths, log retention, and alert routing are live
- rollback and disaster-recovery runbooks are available to the operator

## Roles

Assign these roles before the run:

- facilitator: keeps the sequence moving and records timestamps
- operator: performs the rollout and recovery actions
- observer: confirms evidence quality and calls out gaps

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
- confirm scope enforcement behaves as expected for at least one allowed and one denied action

### 3. End-To-End Scan Journey

Run one complete supported journey against a staging target:

1. start a crawl or scan
2. wait for the job to complete
3. retrieve findings summary
4. generate a report artifact
5. verify artifact retrieval or storage path

If authenticated scanning is part of the customer promise, include that flow too.

<a id="3-pilot-proof-scenario-governed-authenticated-scan"></a>

### 3A. Pilot Proof Scenario: Governed Authenticated Scan

This scenario validates a prepared form-login session through HTTP crawling,
active scanning, and report generation. Use a separate browser profile and
`strategy=client` when validating authenticated Client Spider crawling; that
browser session cannot be used for the active-scan step.

Pre-flight configuration:

- target is a staging app you are authorized to test
- MCP auth is enabled for the pilot client
- the pilot client has only the scopes needed for auth prepare/validate, guided
  crawl, guided attack, passive scan wait, findings, report generation, and
  scan-history evidence; include report-read permission when using MCP readback
- `MCP_POLICY_MODE` is `enforce` or `dry-run`
- `MCP_POLICY_BUNDLE_FILE` or `MCP_POLICY_BUNDLE` points at the pilot policy
  bundle
- the pilot policy bundle allows the staging host and the exact tools used in
  this scenario
- an operator-managed auth profile binds the staging origin, login settings,
  and exact credential reference

Run sequence:

1. Call `zap_auth_session_prepare` with the staging `profileId` and a
   `targetUrl` on that profile's authorized origin.
2. Record the response `X-Correlation-Id` header, `Auth Profile`, `Authorized Origin`,
   `Context ID`, `User ID`, provider, and `Engine Binding`. Keep the raw session
   ID only for this live workflow; retain only its SHA-256 fingerprint.
3. Call `zap_auth_session_validate` with the raw session ID.
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
13. Call `zap_findings_summary` with the scanned target as `baseUrl`, then
    `zap_findings_details` with the same `baseUrl` for one material alert family
    if findings exist.
14. Call `zap_report_generate` and record the report artifact path.
15. If validating report retrieval through MCP, call `zap_report_read`
    with the returned `reportPath` and confirm the artifact is retrievable.
16. Call `zap_scan_history_release_evidence` with `releaseName` set to the pilot
    label, a `target` filter, and a bounded `limit`. Review the bundle summary and
    every warning.
17. Call `zap_scan_history_customer_handoff` with `handoffName` set to the same
    label and the same target filter and limit. Review its Acceptance Checklist
    and redaction contract before attaching the curated summary to a customer
    package. Keep raw ledger JSON internal unless explicitly reviewed and redacted.
18. Query audit events or the configured audit sink for matching
    `policy_decision` events on the crawl/attack/report correlation IDs.
19. Confirm the policy outcome is `allow`, `deny`, `dry_run_allow`, or
    `dry_run_deny` according to the selected policy mode and bundle.

Evidence exports are bounded snapshots of retained, caller-accessible ledger
entries. Labels identify the package; they do not filter the run. Choose the
target filter and limit carefully, check for truncation warnings, and confirm the
selected records belong to this staged exercise. Direct scan launch records do
not establish successful completion. Review report contents separately before
sharing them; the curated summary does not attach or redact report files.

Expected operator evidence:

| Evidence | Required proof |
| --- | --- |
| Correlation IDs | One for auth prepare, auth validate, crawl, attack, passive wait, findings, and report generation. |
| Auth evidence | Session ID SHA-256 fingerprint (never raw), context ID, user ID, provider, engine binding, validation outcome, and diagnostics. |
| Policy evidence | `policy_decision` audit event with bounded tags and the same correlation ID as the tool call. |
| Scan evidence | Guided operation ID plus backend scan ID or queued job ID where available. |
| Passive evidence | Passive scan wait result after crawl and after attack. |
| Findings evidence | Findings summary and one details drilldown when findings exist. |
| Report evidence | Generated report artifact path and readback/storage confirmation where available. |
| Release evidence | `zap_scan_history_release_evidence` bundle with the selected targets, summary counts, and reviewed warnings. |
| Customer handoff | `zap_scan_history_customer_handoff` curated summary with the Acceptance Checklist and redaction contract reviewed; named caveats for unchecked items. |
| Secret handling | No raw secret in client response, logs, audit event, report, ticket, or screenshot. |

Concrete blockers must become follow-up issues before sign-off. Common examples:

- authenticated guided AJAX crawl (`strategy=browser`) is required
- bearer/API-key header injection is required for guided execution
- policy mode cannot emit an audit decision for the pilot tool call
- scan job or operation IDs cannot be correlated with logs
- report artifacts cannot be read back or mapped to durable storage
- the target requires MFA, SSO, CAPTCHA, or another unsupported auth adapter

Do not expand this scenario mid-run to hide a gap. If the path fails on a real
product limitation, write the follow-up issue and mark the pilot proof as failed
or passed with an explicit caveat.

### 4. HA Recovery Check

If the deployment uses multiple MCP replicas:

- terminate or restart one MCP replica while a claimed queued job is active
- confirm another replica resumes polling after lease expiry
- confirm no duplicate scan start occurs

If the deployment is single-replica, record that HA recovery is out of scope for this run.

### 5. Rollback Check

- execute one controlled rollback or rollback rehearsal
- confirm the rollback revision and operator steps are explicit
- confirm health and auth recover after rollback

### 6. Restore Evidence Check

- verify the latest staged backup exists
- execute a staged restore or restore rehearsal using the DR runbook
- record achieved RPO and RTO evidence

## Minimum Evidence To Capture

- deployment render or rollout command output
- health checks for MCP and ZAP
- one authenticated MCP interaction trace
- one policy decision audit event for the governed scan path
- one guided operation ID plus backend scan or job ID where available
- one passive-scan wait result after scan traffic
- one findings summary result
- one generated report artifact reference
- one release/pilot evidence bundle with its warnings reviewed
- one curated customer handoff summary with its acceptance result and caveats
- relevant correlation IDs
- HA failover evidence where applicable
- rollback revision and validation notes
- restore timestamps and outcome
- all gaps written down as explicit follow-up issues

## Exit Criteria

Do not call the environment production-ready unless all of the following are true:

- the supported scan path succeeded end-to-end
- the governed authenticated pilot proof either passed or produced explicit
  follow-up issues for each blocker
- auth and authorization behaved as expected
- policy decisions were observable and matched the configured mode
- no duplicate scan start occurred during failover testing
- rollback and restore evidence are recorded
- the release evidence window and customer handoff checklist were reviewed;
  unchecked items and warnings have an explicit owner and acceptance decision
- unresolved gaps have a documented owner and acceptance decision

Accepted caveats must state the narrower deployment or scan path being approved.
A failed required workflow is a failed proof.

## Suggested Sign-Off Comment

Use this structure to record the staged simulation decision:

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
- rollback check
- restore evidence check

Evidence:
- links or artifact references
- representative correlation IDs
- policy_decision audit event reference
- guided operation ID and backend scan/job ID
- report artifact location
- release evidence bundle summary, warnings, and customer handoff checklist result
- restore timestamps / rollback revision

Result:
- pass / pass with accepted caveats / fail

Principal engineering sign-off:
- approved / not approved
- name
- date
```

## Related Docs

- [Production Readiness Checklist](https://danieltse.org/mcp-zap-server/operations/production-checklist/)
- [Multi-Replica Deployment Requirements](https://danieltse.org/mcp-zap-server/operations/local-ha-compose/)
- [Queue Coordinator and Worker Claims](https://danieltse.org/mcp-zap-server/operations/queue-coordinator-leader-election/)
- [Release Evidence Handoff Runbook](https://danieltse.org/mcp-zap-server/operations/release-evidence-handoff-runbook/)
- [Auth Bootstrap Failure Runbook](https://github.com/dtkmn/mcp-zap-server/blob/main/docs/operator/runbooks/AUTH_BOOTSTRAP_FAILURE_RUNBOOK.md)

Maintain rollback and disaster-recovery procedures for your own deployment.
The repository does not provide provider-specific runbooks for those operations.
