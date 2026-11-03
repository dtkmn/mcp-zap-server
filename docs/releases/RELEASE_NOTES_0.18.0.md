# v0.18.0 Release and Upgrade Guide

This guide covers the 0.18.0 release candidate and upgrades from `v0.17.1`. Version `0.18.0` provides one set of scan tools and queues every new crawl and active scan. Back up persistent data
and apply V9 before starting new PostgreSQL workers.

| Installation | Version selected for new installs |
| --- | --- |
| Standalone Compose, Helm chart and MCP Registry source | Scanner `v0.18.0` |
| Claude Desktop MCPB `0.1.1` | Scanner `v0.18.0` |
| MCP ZAP Connect for Codex and Claude Code `0.18.0` | Verified scanner checkout and image `v0.18.0` |

Check [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases)
for the tag and expected Desktop downloads. Verify the scanner image for your
platform on GHCR or Docker Hub before deployment. Until those artifacts are
available, use a [source build](../../CONTRIBUTING.md#local-stack) to try
0.18.0, or follow the [v0.17.1 guide](RELEASE_NOTES_0.17.1.md) for the
published predecessor. See the [Unreleased changelog](../../CHANGELOG.md#unreleased).

## Main Compatibility Changes

### Codex and Claude Code Plugin Installation

When the `v0.18.0` repository tag is published, select it in your Codex or
Claude Code marketplace configuration to install MCP ZAP Connect `0.18.0`.
Selecting a tag does not update an existing marketplace automatically. The existing
`v0.17.1` tag still contains plugin `0.5.0`; it cannot deliver these new files.
See [plugin installation and updates](https://danieltse.org/mcp-zap-server/getting-started/agent-guided-setup/#versions-and-release-pins).

For a fresh setup, plugin `0.18.0` verifies the checkout against the official
`v0.18.0` tag and requires the matching `dtkmn/mcp-zap-server:v0.18.0` image.
Setup, recovery and new scan workflows require HTTPS access to `api.github.com`
to verify the release. Existing MCP connections can still make status calls locally.

Updating the plugin preserves your scanner checkout, settings, credentials and storage.
If the scanner still uses `v0.17.1`, follow the upgrade steps below before requesting a
new scan through the `0.18.0` skill. All three plugin manifests now share the repository
release version. A plugin-only fix ships in a new repository release.

### Scanner Server

| Area | Published v0.17.1 | Scanner v0.18.0 |
| --- | --- | --- |
| MCP tools | Default scan tools with an optional expert catalog | One set of scan tools |
| New crawls and active scans | Local in-memory deployments normally start directly; queue execution is also available | Always admitted through the scan-job queue with either storage backend |
| Scan launch records | No new launch-receipt protection | Start attempts and accepted receipts protect against a second start after an uncertain outcome |
| In-memory storage | Queue and default in-memory history are process-local | Still process-local; a separate persistent launch journal protects outstanding native work |
| PostgreSQL scan-job schema | Flyway V1–V8 | Requires all migrations through V9 |
| Controller replacement | No receipt-aware recovery contract | Outstanding work can remain held; automatic adoption or replay is not provided |

Queue admission is not confirmation that ZAP has started scanning. Keep the
Operation ID returned by `zap_crawl_start` or `zap_attack_start`, poll the
corresponding status tool, and use the matching stop tool when needed. Raw ZAP
scan IDs and queue job IDs are not interchangeable with MCP Operation IDs.
Status, stop, findings and report calls are not new queued scan starts.

Tool reply headings have changed. If you copied the v0.17.1 authentication
example, remove the check for the exact “Guided auth session prepared.” heading.
After checking the MCP reply for errors, extract the session ID from the decoded
tool text and require it to be present. This works with both versions.

```sh
AUTH_SESSION_ID=$(printf '%s\n' "$PREPARE_TEXT" | sed -n 's/^Session ID: //p')
test -n "$AUTH_SESSION_ID" || exit 1
```

For crawl and active scan starts, use the `Operation ID:` field in the reply.
An `idempotencyKey` now applies to queued admission for all new crawls and active scans;
reusing it with different parameters is rejected. It does not provide recovery
after ZAP loses its state or after an in-memory queue is lost.

The agent or client still coordinates the workflow: import or prepare
authentication if required, crawl or attack within the authorized scope, track
completion, wait for passive analysis, then read findings and retrieve reports.
There is no new server-managed replacement for native Automation Framework plans.

## Migrate Tool Calls and Permissions

Refresh tool discovery after upgrading and update saved prompts, integrations,
scope grants and runtime policy bundles to use the current catalog.

| Former interface or workflow | Supported replacement or limitation |
| --- | --- |
| Direct and explicit-queue crawler start aliases | `zap_crawl_start`, `zap_crawl_status`, `zap_crawl_stop` |
| Direct and explicit-queue active-scan aliases | `zap_attack_start`, `zap_attack_status`, `zap_attack_stop` |
| Existing scan-policy listing and inspection | The same `zap_scan_policies_list` and `zap_scan_policy_view` names, now in the scan tool catalog |
| Family-specific API definition imports | `zap_target_import` |
| Raw findings, snapshot and diff calls | Findings tools; `zap_findings_snapshot` and `zap_findings_diff` keep their names |
| Raw report generation | `zap_report_generate`, with supported HTML/JSON formats, followed by `zap_report_read` or `zap_report_read_chunk` |
| Raw context/user setup | Operator-managed auth profiles and `zap_auth_session_prepare` / `zap_auth_session_validate` |
| Automation Framework plan run, status and artifact tools | Retired; call the scan tools in sequence. Native YAML plans require a separately managed ZAP workflow |
| Raw queue administration, retry/dead-letter controls, policy editing and policy-preview aliases | No equivalent raw MCP entrypoint |

The MCP server does not list arbitrary report templates, the full AJAX
Spider URL inventory, or all ZAP contexts and users. It does not expose policy
editing. Existing operator-managed policies remain usable; their internal
editing support has not been removed. See the [scan tool catalog and
limits](../src/content/docs/getting-started/tool-surfaces.md) and
[tool migration guide](../src/content/docs/getting-started/tool-migration.md).

<details>
<summary>Removed v0.17.1 expert tool names</summary>

| Family | Removed names |
| --- | --- |
| Active scans | `zap_active_scan_start`, `zap_active_scan_as_user`, `zap_active_scan_status`, `zap_active_scan_stop` |
| Traditional Spider | `zap_spider_start`, `zap_spider_as_user`, `zap_spider_status`, `zap_spider_stop` |
| AJAX Spider | `zap_ajax_spider`, `zap_ajax_spider_results`, `zap_ajax_spider_status`, `zap_ajax_spider_stop` |
| Client Spider | `zap_client_spider_start`, `zap_client_spider_status`, `zap_client_spider_stop` |
| Explicit queue submission | `zap_queue_active_scan`, `zap_queue_active_scan_as_user`, `zap_queue_spider_scan`, `zap_queue_spider_scan_as_user`, `zap_queue_ajax_spider`, `zap_queue_client_spider_scan` |
| Queue administration | `zap_scan_job_cancel`, `zap_scan_job_dead_letter_list`, `zap_scan_job_dead_letter_requeue`, `zap_scan_job_list`, `zap_scan_job_retry`, `zap_scan_job_status` |
| Engine and findings inventory | `zap_hosts`, `zap_sites`, `zap_urls`, `zap_alerts`, `zap_alert_details`, `zap_alert_instances`, `zap_get_findings_summary` |
| Authentication/context/user management | `zap_auth_test_user`, `zap_context_auth_configure`, `zap_context_upsert`, `zap_contexts_list`, `zap_user_upsert`, `zap_users_list` |
| Schema imports | `zap_import_graphql_schema_file`, `zap_import_graphql_schema_url`, `zap_import_openapi_spec_file`, `zap_import_openapi_spec_url`, `zap_import_soap_wsdl_file`, `zap_import_soap_wsdl_url` |
| Policies and reports | `zap_policy_dry_run`, `zap_scan_policy_rule_set`, `zap_generate_report`, `zap_view_templates` |
| Automation Framework | `zap_automation_plan_run`, `zap_automation_plan_status`, `zap_automation_plan_artifacts` |

`zap_scan_policies_list`, `zap_scan_policy_view`, `zap_findings_snapshot` and
`zap_findings_diff` retain their names and are available in the scan tool catalog.

</details>

Restricted clients need `zap:scan:crawl:run` for crawls,
`zap:scan:attack:run` for attacks, `zap:scan:read` for status and
`zap:scan:stop` for stopping. Policy inspection requires the separate
`zap:scan:policy:read` grant. Former expert scopes are not automatically mapped:
for example, `zap:scan:active:run` does not authorize `zap_attack_start`.
Preserve existing target restrictions and other permission checks.

Retired tool calls receive the generic MCP `Unknown tool` response. Wildcards
or authorization `warn` / `off` settings cannot restore an unregistered tool.
Remove retired tool names from runtime policy rules; policy validation rejects
names outside the current catalog. Review replacements individually rather than
widening a client's permissions to make a former workflow pass.

Finish or deliberately stop existing expert scans and native plans before the
cutover. Their IDs and plan paths cannot be converted into MCP Operation IDs.
Previously issued, valid signed Operation IDs for direct scans retain status
and stop compatibility within their signing-key lifetime. Preserve the signing
key and caller/workspace identity when that compatibility is needed; it does
not enable new direct starts. The configured key is
`mcp.server.guided.operation-signing-secret`, with `JWT_SECRET` as its fallback.
Without a configured key, the process-generated key does not survive a restart.

### CI Consumers

The CI security-gate helper now requires the crawl/attack start and
status tools, `zap_findings_summary`, `zap_report_generate` and
`zap_passive_scan_wait` during discovery, including for crawl-only runs. It
does not fall back to former expert aliases. Use a compatible server and grant
the required tool permissions before starting the gate.

Only `traditional-html-plus` and `traditional-json-plus` are accepted as the
action's `report-template`; unsupported templates are rejected before scanning.
Failed, cancelled or uncertain-start operations stop the gate instead of
submitting another scan. Snapshot support is discovered through
`zap_findings_snapshot`. If `fail-on-new-findings=true` and
`baseline-mode=enforce`, missing or incomparable baseline evidence fails the
gate; a changed add-on inventory can require a freshly reviewed baseline.

Existing output schemas remain result v2, snapshot/diff v3 and manifest v1.
These schema versions already existed in v0.17.1; this is not another format
migration. See [CI Gate Contracts](../scanning/CI_GATE_CONTRACTS.md).

## Configuration and Monitoring Cleanup

Remove obsolete mode and direct-operation settings from copied deployment
configuration. Keep the queue limits, authentication, target restrictions and
launch-safety persistence configuration in use.

| Obsolete environment variable | Obsolete application property |
| --- | --- |
| `MCP_SERVER_TOOLS_SURFACE` | `mcp.server.tools.surface` |
| `MCP_PROTECTION_OPERATION_STALE_AFTER_SECONDS` | `mcp.server.protection.operationStaleAfterSeconds` |
| `MCP_PROTECTION_WORKSPACE_QUOTA_MAX_DIRECT_SCANS` | `mcp.server.protection.workspace-quota.max-direct-scans` |
| `MCP_PROTECTION_WORKSPACE_QUOTA_MAX_AUTOMATION_PLANS` | `mcp.server.protection.workspace-quota.max-automation-plans` |
| `MCP_PROTECTION_BACKPRESSURE_MAX_DIRECT_SCANS` | `mcp.server.protection.backpressure.max-direct-scans` |
| `MCP_PROTECTION_BACKPRESSURE_MAX_AUTOMATION_PLANS` | `mcp.server.protection.backpressure.max-automation-plans` |

These settings no longer control the application; leaving them in an existing
environment does not restore the old behavior. Use the retained queue quotas
and backpressure limits for new scans. The retired operation-stale
timeout is not a queue lease, scan timeout or history-retention setting.

Retiring `zap_policy_dry_run` removes the standalone preview tool, not live
runtime-policy enforcement or its dry-run evaluation mode. Keep valid policy
settings and restrictions. Policy dry-run records decisions without enforcing
them; it does not turn an authorized scan request into a no-scan simulation.

The `mcp.zap.operations.active` metric, exported to Prometheus as
`mcp_zap_operations_active`, is gone, including its `direct_scan` and
`automation_plan` series. Replace dashboard and alert queries with the queue's
job-status and claim metrics as appropriate; missing old series do not mean
zero running jobs. See [Observability](../src/content/docs/operations/observability.md).

Manual reconciliation is not part of the current MCP catalog. The intermediate
development tools `zap_scan_job_reconciliation_status` and
`zap_scan_job_reconcile` are absent; granting `zap:scan:queue:reconcile` cannot
expose them. There is no supported MCP command to attach a native scan ID or
clear an uncertain launch hold.

If upgrading an intermediate source build that used them, also remove
`ZAP_SCAN_RECONCILE_OPERATOR_CLIENT_IDS`,
`zap.scan.jobs.launch-safety.operator-client-ids` and Helm
`mcp.launchSafety.operatorClientIds`. These were not v0.17.1 release settings.
The separate add-on maintenance operator settings remain in use. Keep the
launch journal and directory-separation safeguards, including retained
operator-managed automation roots; removing MCP plan tools does not make those
storage boundaries obsolete.

## Storage Requirements

Choose the scan-job backend deliberately and preserve its existing data.
Changing the backend setting does not migrate jobs, history or outstanding
launch ownership. With the bundled environment mapping, scan history defaults
follow the job backend and database connection unless history settings override
them. Check those overrides and the independently configured JWT revocation
store when planning backups or a storage change.

| Scan-job backend | State to preserve | Deployment requirements |
| --- | --- | --- |
| `in-memory` | Persistent private launch journal, in addition to report workspaces and any separately persistent history | One controller owns the journal. Queue records and any in-memory history remain process-local; the journal is not their backup |
| `postgres` | Job database, including V9 launch-attempt data, plus separately configured history/revocation databases and report workspaces | All workers controlling the same ZAP instance use the same job store. Database availability and migrations are required |

### In-Memory Jobs

Set `ZAP_SCAN_LAUNCH_JOURNAL_DIRECTORY` to private, persistent, MCP-writable
storage. The application default is `${user.home}/.mcp-zap/scan-launch-safety`;
the provided container configuration uses
`/var/lib/mcp-zap/scan-launch-safety/receipts` on a dedicated mount. Keep the
journal separate from report, automation and content-import directories, and
do not mount it into ZAP. Unreadable, corrupt or unwritable recovery state fails
closed.

Preserve the launch-safety volume when replacing containers. Helm creates a
separate journal PVC for in-memory jobs and uses one MCP replica with `Recreate`
rollouts; expect a brief service interruption. Configure its StorageClass or use
`mcp.launchSafety.persistence.existingClaim` with the actual retained journal
claim. It must be different from the ZAP workspace claim. Retain the required
ownership and write permissions.

A legacy in-memory deployment has no durable launch records for a newly created
journal to recover. Finish or stop its existing work and establish that no old
scan start remains in flight before resuming submissions. An empty new journal
does not establish that ZAP is idle.

### PostgreSQL Jobs

Apply all pending Flyway migrations through **V9**, which adds
`scan_jobs.launch_attempt_json`. The application and Helm migration bundles
contain the same migration. Migration execution is disabled by default; enable
the configured migration mechanism or apply the bundled SQL through the
deployment's existing migration process before new workers start.

An explicitly selected PostgreSQL job store must initialize successfully. A
missing URL or unavailable store stops startup; the legacy `fail-fast=false`
setting no longer permits a fallback to empty in-memory state. Unknown backend
names are rejected. The legacy `ZAP_SCAN_QUEUE_STORE_BACKEND` environment alias
remains supported; prefer `ZAP_SCAN_JOBS_STORE_BACKEND` for new configuration.

With Helm, set `mcp.scan.jobs.store.backend=postgres` explicitly when the runtime
configuration comes from `mcp.envFrom`. The chart must know the selected backend
to choose storage and rollout behavior. PostgreSQL mode stores launch receipts
in the database and does not create or mount the memory-journal PVC. Existing
literal backend entries in `mcp.env` remain supported.

## Safe Upgrade Sequence

1. Save the current deployment configuration and credentials. Back up the job
   database, any existing launch journal and report workspaces separately;
   verify the restore procedure. Preserve signing keys needed for existing
   operation handles and tokens.
2. Pause new submissions. Finish or deliberately stop outstanding scans and
   plans where possible, using the old deployment's supported controls. A stop
   request or timeout alone is not proof that native work ended.
3. Stop **all old MCP controllers** sharing the engine or job store. Prevent
   autoscaling, GitOps or another supervisor from restarting them during the
   cutover. Do not overlap v0.17.1 workers with receipt-aware workers.
4. Preserve the selected backend. For PostgreSQL, migrate through V9 before
   starting new workers. For in-memory jobs, provision or retain the separate
   private journal and use one controller. Check permissions and actual claim
   identities before replacing a deployment.
5. Start the new application with chart/configuration and migrations from the
   same reviewed source. A Helm
   migration hook does not stop old application pods by itself. When evaluating
   0.18.0 before image publication, build its source explicitly. Confirm the
   running image matches the intended version before resuming submissions.
6. Rediscover tools through the intended authenticated client. Verify its
   scopes, policy rules, existing report readback, backend and outstanding job
   states before resuming submissions. Deployment health does not establish
   that old scans were recovered.
7. Keep unresolved work held. Do not delete receipts, select an empty store or
   submit a replacement scan just to bypass an unknown outcome.

Do not downgrade to an older controller while receipt-bearing work remains
outstanding: it cannot enforce the new held states. V9 does not provide an
automatic down migration or a general rollback guarantee. Review the database,
engine state and backups together before a deliberate rollback; restoring an
older database alone cannot undo work already accepted by ZAP.

For Helm claim retention, reinstallation and storage changes, follow
[Scan Launch Safety Storage](../../helm/mcp-zap-server/README.md#scan-launch-safety-storage)
and the [controller cutover procedure](../../helm/mcp-zap-server/README.md#preserve-launch-ownership-before-replacing-controllers).
Changing a claim reference does not copy its files. Reuse the actual retained
workspace and separate journal claims, or the preserved PostgreSQL database.

## Scan Outcomes and Recovery Limits

| Observation | What the server does | Interpretation or limitation |
| --- | --- | --- |
| No capacity for a scan | Keeps it queued until admission is possible | ZAP has not started that queued request yet |
| Native start is about to be sent | Records `STARTING` and reserves capacity before dispatch | The record alone is not an acknowledgment from ZAP |
| Valid start reply is recorded | Tracks the accepted native scan as `RUNNING` | This is not scan completion or a clean findings result |
| Start may have been accepted, but its reply/receipt is uncertain | Holds `START_OUTCOME_UNKNOWN` and reserves capacity; does not send another start automatically | A timeout is not a confirmed failure to start |
| A trusted late reply arrives for the same unresolved attempt | Can attach the receipt to that attempt and resume tracking or an already requested cancellation | It cannot attach an unrelated or reused native ID |
| Cancellation cannot be confirmed | Keeps cancellation unresolved and capacity reserved | Sending stop does not prove the scan stopped |
| Controller ownership is lost or replaced | Conservatively holds outstanding receipts | Lease expiry does not prove ZAP is idle or authorize automatic takeover |

Scan START requests are not automatically repeated by the HTTP transport.
An uncertain HTTP Spider start does not trigger an AJAX start as a fallback.
The `auto` crawl strategy uses HTTP; choose `client` or `browser` explicitly
when browser crawling is needed. Failures before native dispatch can still use
the configured bounded retry policy; an explicit supported busy rejection
follows its waiting policy.
Status polling failures preserve the known scan rather than restarting it.

The same protection applies to active scans, traditional Spider, AJAX Spider,
Client Spider and supported authenticated variants. AJAX still uses a synthetic
tracking ID around ZAP's global lifecycle; keep all controllers on the same job
store and avoid unrelated direct AJAX callers against that engine.

Persistence protects against unsafe duplicate starts; it is not automatic scan
recovery. A saved native ID does not by itself prove ownership after controller
replacement. PostgreSQL workers do not automatically adopt a foreign worker's
expired receipt, and memory-journal retention does not restore in-memory jobs.

The server cannot reliably identify every ZAP-only restart or session
replacement. Endpoints, version strings and empty scan lists do not establish
engine identity; native IDs can be reused. Work can remain held after either
controller or engine replacement. No MCP reconciliation tool is available to
clear these holds or manually resume tracking. Keep the engine lifecycle under
operator control and preserve recovery state while investigating.

See [Queue Retry and Launch Recovery](../src/content/docs/operations/scan-queue-retry-policy.md)
for the configured timeouts, busy waiting, cancellation and historical cleanup
job behavior. The retained limits are safety controls, not a promise of
uninterrupted recovery.

## Policies and Add-on Maintenance

Use `zap_scan_policies_list` and `zap_scan_policy_view` before selecting a policy
for `zap_attack_start`. Starting a scan preserves the selected policy's enabled
rules and does not change the default policy. Policy inspection reads shared
engine configuration; it does not freeze that configuration for a later scan.
See [Scan Policy Selection](../src/content/docs/scanning/scan-policy-controls.md).

The new `zap_addon_inspect`, `zap_addon_update` and `zap_addon_update_status`
tools provide operator-controlled add-on maintenance. Updates are disabled by
default and require enforced authentication/authorization, an allowlisted
operator with the exact update scope, an approved installed add-on, dependency
acknowledgment, durable maintenance state and an idle, exclusively controlled
engine. This update workflow supports one controller, `single-node`
coordination and in-memory jobs; it is not enabled for PostgreSQL/HA workers.
For `zap_addon_inspect`, ZAP may need outbound access to the ZAP Marketplace
to fetch metadata on its first lookup. If ZAP reports no eligible candidate,
`zap_addon_update` sends no install request. An update must match the
installed and candidate versions just inspected.
Marketplace may add or update dependencies alongside the approved add-on; the
tool does not pin an arbitrary version or install a new root add-on.

Add-on maintenance uses its own safety gate, not the scan-job queue. Unknown or
restart-required outcomes are reported for operator action, with quarantine
where required; the tools do not restart ZAP or force an unlock. Its private
maintenance state is separate from the scan launch journal. Follow
[Add-on Maintenance](../src/content/docs/operations/engine-addons.md) before
opting in.
