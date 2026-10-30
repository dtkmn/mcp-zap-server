---
title: "Scan Queue Retry and Backoff Policy"
editUrl: false
description: "Retry budgets, uncertain-start holds and cancellation for queued scans."
---
## Scope

This policy applies to queue-managed scan job families:

- `ACTIVE_SCAN`, `ACTIVE_SCAN_AS_USER`
- `SPIDER_SCAN`, `SPIDER_SCAN_AS_USER`, `AJAX_SPIDER`
- `CLIENT_SPIDER` (in `v0.13.0`; see [Client Spider](../../scanning/client-spider/))

## Default Policy

| Scan family | Max attempts | Initial backoff | Multiplier | Max backoff |
| --- | --- | --- | --- | --- |
| Active family | 3 | 2000 ms | 2.0 | 30000 ms |
| Spider family, including queued AJAX Spider and Client Spider | 2 | 1000 ms | 2.0 | 10000 ms |

`maxAttempts` includes the first execution attempt.

## ZAP API Connection and Read Timeouts

All calls from this server to ZAP use configurable HTTP timeouts:

| Environment variable | Application property | Default |
| --- | --- | --- |
| `ZAP_API_CONNECT_TIMEOUT_MS` | `zap.server.connect-timeout-ms` | `5000` (5 seconds) |
| `ZAP_API_READ_TIMEOUT_MS` | `zap.server.read-timeout-ms` | `10000` (10 seconds) |

Both values must be positive; startup rejects zero or negative values. The connection timeout limits connection establishment, and the read timeout limits inactivity while receiving a response. They are per-request limits, not a total scan duration or a strict overall call deadline. Configure them in `.env`/Docker Compose, deployment environment variables, or Helm's `mcp.zapClient.connectTimeoutMs` and `mcp.zapClient.readTimeoutMs` values.

A timeout does not prove that ZAP rejected an action: ZAP may still process it or may have completed it before its response was lost. An uncertain scan start is held rather than automatically submitted again. Cancellation stays unconfirmed until native status establishes termination. The current MCP catalog does not expose manual launch-hold resolution.

`ZAP_CONNECTION_TIMEOUT` controls ZAP's connections to scan targets in seconds and is applied at startup through `zap.initialization.connectionTimeoutInSecs`. An existing `ZAP_INIT_CONNECTION_TIMEOUT` value takes precedence; when neither variable is set, the effective default remains 300 seconds. The example environment and Helm value `mcp.scan.limits.connectionTimeoutInSecs` specify 60 seconds. These values were previously ignored and now take effect. The unused application property `zap.scan.limits.connectionTimeoutInSecs` has been removed; deployments that set that property directly should use `zap.initialization.connectionTimeoutInSecs`.

## Launch Outcomes

Before dispatching a native scan-start request, the queue records a launch attempt.
The attempt remains associated with that job until its outcome is resolved.

| State | Meaning | Capacity |
| --- | --- | --- |
| `QUEUED` | Waiting for admission to the engine or a confirmed busy rejection | Subject to the ordinary queue rules |
| `STARTING` | A recorded native start is in flight | Reserved |
| `START_OUTCOME_UNKNOWN` | ZAP may have accepted the start, but its outcome is unconfirmed | Reserved |
| `RUNNING` | The accepted native scan ID is recorded | Reserved until completion or cancellation is established |

A timeout, malformed reply, lost worker claim or missing native scan ID does not
prove that a scan never started. An unresolved launch cannot be retried, replayed
as a dead-letter job or treated as cancelled merely to release its slot. Its
worker lease may expire while its capacity remains reserved. Other jobs can use
the remaining capacity.

Only failures known to precede native scan acceptance are eligible for the
bounded startup retry policy. Explicit engine-busy rejection follows the
separate waiting policy below. A late scan ID is accepted only while the exact
launch attempt and resolution version still own that work.

Cancellation during `STARTING` or `START_OUTCOME_UNKNOWN` records intent. This
applies to active scans and every crawler family, including authenticated scans.
Without a trusted native ID, the queue cannot claim that a stop has completed.

## Restart and Ownership

When upgrading from a version without launch receipts, drain outstanding work
where possible, then stop all old controllers before starting receipt-aware
workers. Preserve the job database and journal and apply migration V9 for Postgres.
Surviving work may remain held; current MCP tools cannot manually resume it.
Do not run old and new workers against the same engine and queue during this
upgrade: older workers
do not understand the held launch states. Normal joins among receipt-aware
workers follow the ownership rules below. Do not downgrade to an older controller
while outstanding receipt-bearing work remains.

Postgres stores retain launch attempts and accepted scan receipts with their job
records. Migration V9 adds the launch receipt fields. An explicitly configured
Postgres job store must initialize successfully; a missing URL or unavailable
store stops startup instead of falling back to empty in-memory state. The legacy
job-store `fail-fast=false` setting does not permit this unsafe fallback.

In-memory job history remains process-local, but a private durable
launch journal protects outstanding native work when the controller restarts.
Losing in-memory job history does not establish that ZAP is idle. Unresolved
journal entries can hold new queued starts. The current MCP catalog cannot
manually clear those holds.

Keep the journal on persistent, MCP-only storage outside report and automation
directories. Do not delete it to make a held job retryable. The provided container
configuration uses `/var/lib/mcp-zap/scan-launch-safety/receipts`, separately from
shared ZAP workspaces. Local installations can configure a private persistent
directory through `ZAP_SCAN_LAUNCH_JOURNAL_DIRECTORY`.
Unreadable, corrupt or unwritable recovery state fails closed.

Helm's in-memory mode requires one MCP controller, retains its journal on a
separate PVC, and uses `Recreate` rollouts so two controllers cannot own that
journal at once. Controller replacement briefly interrupts service. Configure
an existing volume through `mcp.launchSafety.persistence.existingClaim` if needed.
Select Postgres through `mcp.scan.jobs.store.backend=postgres` for multiple
workers; that mode uses the database receipts and does not mount the memory
journal PVC. Existing literal backend entries in `mcp.env` remain supported.
When using `mcp.envFrom`, declare the backend explicitly so the chart can choose
the matching persistence and rollout behavior. The chart fixes the journal path
to its private mount and rejects overrides that would bypass that selection.
Use a claim separate from ZAP's workspace volume.

Outstanding receipts are held conservatively after controller replacement.
Controller replacement alone does not establish ownership of a recorded native ID.

Adding a Postgres replica preserves another worker's live claimed work. When
that foreign ownership expires, recovery holds its receipts before using their
native IDs. An expired lease establishes lost liveness, not an idle engine.
These jobs are not automatically adopted, and the current MCP catalog cannot
manually adopt them.

ZAP's endpoint, version and empty scan lists do not provide trustworthy proof of
engine identity. Do not rely on an old scan ID when ownership is unconfirmed.
A controller restart and a ZAP restart are different events, and native IDs can
be reused.
Keep engine lifecycle changes under the same operator's control. Outstanding
work can remain held after an engine restart or session replacement. The controller
cannot automatically detect every engine-only restart.

## Operator Reconciliation

Manual launch reconciliation is unavailable through MCP. The current catalog
does not register `zap_scan_job_reconcile` or
`zap_scan_job_reconciliation_status`. Setting
`ZAP_SCAN_RECONCILE_OPERATOR_CLIENT_IDS`, configuring Helm operator IDs or granting
`zap:scan:queue:reconcile` does not make these tools available.

Internal launch protection remains active. Uncertain jobs retain their holds and
capacity reservations, and unresolved launches are not automatically started
again. A late trusted receipt can still resolve its exact outstanding attempt.
Use an existing valid guided Operation ID to check its status when available.
Checking status does not provide manual hold resolution.

There is no supported MCP command to attach a native scan ID, retire an unresolved
launch receipt or manually restore tracking after controller replacement.
Held work may therefore continue to reserve capacity or block new queued starts.
Preserve the job database and journal; do not delete recovery state or submit a
replacement scan to bypass an unknown outcome. An idle snapshot alone cannot
prove that a delayed start will never execute.

## Waiting for a Busy Engine

An explicit engine-busy rejection keeps the job `QUEUED (waiting for engine)` without consuming a startup attempt. The queue reuses the scan family's backoff settings, counting busy responses separately. Guided status shows the waiting reason and next retry time. Waiting jobs can be cancelled normally.

`ZAP_SCAN_QUEUE_ENGINE_BUSY_MAX_WAIT_MS` sets the maximum wait from the first busy response. The default is `180000` (three minutes); `0` disables waiting and fails immediately. Negative values are rejected. This limit applies to engine-busy waiting, not time spent queued before the first launch attempt. It is separate from the startup retry budget.

The waiting timestamp and backoff count are persisted, so restarts and worker changes do not renew the budget. On the next dispatch cycle after expiry, the job becomes `FAILED` with a timeout reason, even if capacity is full or its next retry is later. A start already in flight retains the existing dispatch timeout and claim rules.

Engine integrations identify explicit busy responses; the queue does not guess from error-message text. ZAP's `scan_in_progress` launch response is currently recognized. Other failures follow the launch-outcome rules above; an unknown outcome does not consume retries by launching another scan. This handles the period when AJAX Spider reports stopped but ZAP is still cleaning up; it does not repair ZAP's immediate-stop error.

Configure this environment variable alongside the existing retry controls in `.env`/Docker Compose or your deployment's environment settings. PostgreSQL deployments require migration V7, which adds the two busy-wait fields; standard Flyway startup and the Helm migration bundle include it.

## Shared Cancellation and Cleanup Retry Window

`ZAP_SCAN_QUEUE_CANCEL_MAX_WAIT_MS` (`zap.scan.queue.cancel-max-wait-ms`) sets one stop retry window for cancellation of running queue jobs, AJAX cancellation during startup, and abandoned active, traditional Spider, or Client Spider cleanup. It defaults to `30000` (30 seconds) and must be positive. All paths use the scan family's existing backoff without consuming startup attempts.

The former `ZAP_SCAN_QUEUE_AJAX_CANCEL_MAX_WAIT_MS` environment variable and `zap.scan.queue.ajax-cancel-max-wait-ms` property remain fallback aliases for this shared setting. The new setting takes precedence. Configure it in `.env`/Docker Compose, deployment environment variables, or Helm's `mcp.env` list.

A slow native stop or confirmation request can delay other queue changes while
it remains in flight. Ownership cannot be reassigned during that stop
operation.

The original deadline and retry schedule survive worker changes and PostgreSQL-backed restarts. Repeating cancellation while it is pending preserves the deadline. Once the window expires, automatic stop retries end and status reports that cancellation is unconfirmed and the scan may still be running. An explicit new cancellation request opens a new window. A stop acknowledgement alone does not release capacity: native status must also confirm termination. Manual hold resolution is not exposed through the current MCP catalog.

Cancelling a running active scan, traditional Spider, or Client Spider, including authenticated scans, saves cancellation on the existing job before attempting to stop its ZAP scan ID. A failed stop remains pending and retries within this window; it does not create a cleanup record. Other scans can continue using the remaining capacity.

## AJAX Cancellation During Startup

Cancelling an AJAX queue job records a durable cancellation request. If ZAP rejects the stop while its crawler initializes, the job shows `cancellation pending` and the queue retries using the spider family's backoff. Stop failures do not consume startup attempts, and a generic ZAP internal error is not interpreted as proof of initialization.

The job retains its AJAX slot until native status confirms the crawler is stopped. Cancelling a job whose start is already in flight also retains ownership. If startup never returns a scan ID, or persisted jobs disagree about who owns the crawler, the queue does not issue a global stop and cancellation remains unconfirmed. Ordinary queued jobs that have not started are cancelled immediately without calling ZAP.

Guided AJAX starts and cancellation attempts share a lifecycle lock, including across PostgreSQL workers, because ZAP's stop API is global. Use the same job store for all replicas controlling one ZAP instance, and do not start unrelated AJAX crawls through ZAP's API/UI while this server owns it: synthetic AJAX IDs cannot fence those external callers.

If an AJAX start returns its ID after dispatch times out, the queue adopts it on the same launch attempt. Timeout alone does not request cancellation. An existing cancellation request remains pending and uses the same stop retry window. Delayed responses must still match the job's launch attempt and resolution version; they cannot take ownership of a resolved attempt.

The cancellation deadline bounds retry scheduling, separately from the connection and read timeouts above. An in-flight stop can outlast the cancellation retry window; its lifecycle lock is retained until the call returns, and a timed-out stop leaves cancellation unconfirmed. PostgreSQL deployments require migration V8, included in the application and Helm migration bundles.

## Late Scan Receipts and Existing Cleanup Jobs

When an active scan, traditional Spider, or Client Spider returns its native ID after dispatch times out, the queue records it against the same unresolved launch attempt. This includes authenticated scans. The job can then resume status tracking, or finish an explicitly requested cancellation. A late receipt cannot change an already resolved attempt, and does not authorize stopping an unrelated or reused native ID.

Cleanup jobs retained from earlier versions remain visible with `Cleanup for Job ID` / `cleanupForJob`. They retain their scan family's capacity and use the shared cancellation retry window. They cannot be retried or replayed as new scans. PostgreSQL retains these records; ordinary in-memory history remains process-local.

After the retry deadline, an unresolved cleanup remains visible as cancellation unconfirmed and continues to reserve capacity. Background status polling can confirm the scan has finished. The raw cleanup-job cancellation alias is retired, and no generic guided cleanup-job control replaces it. Historical cleanup-job IDs cannot be substituted for guided operation handles. Controller or engine replacement does not establish ownership of a saved native ID, and the current MCP catalog cannot manually clear a resulting hold.

## Retryable Errors

Auto-retry applies to transient startup failures known to occur before native dispatch. A possible accepted start remains held instead.

Runtime polling failures preserve the running job and its engine scan ID for the next status refresh; they do not restart the scan or consume startup attempts.

Non-retryable paths:

- invalid input rejected before enqueue
- cancelled jobs are not automatically restarted
- inconsistent internal running state requiring explicit recovery

## Backoff Formula

For attempt `n`:

`delayMs = min(maxBackoffMs, round(initialBackoffMs * multiplier^(n - 1)))`

## Runtime Overrides

- `ZAP_SCAN_QUEUE_ENGINE_BUSY_MAX_WAIT_MS`
- `ZAP_SCAN_QUEUE_CANCEL_MAX_WAIT_MS`
- `ZAP_SCAN_QUEUE_RETRY_ACTIVE_MAX_ATTEMPTS`
- `ZAP_SCAN_QUEUE_RETRY_ACTIVE_INITIAL_BACKOFF_MS`
- `ZAP_SCAN_QUEUE_RETRY_ACTIVE_MAX_BACKOFF_MS`
- `ZAP_SCAN_QUEUE_RETRY_ACTIVE_MULTIPLIER`
- `ZAP_SCAN_QUEUE_RETRY_SPIDER_MAX_ATTEMPTS`
- `ZAP_SCAN_QUEUE_RETRY_SPIDER_INITIAL_BACKOFF_MS`
- `ZAP_SCAN_QUEUE_RETRY_SPIDER_MAX_BACKOFF_MS`
- `ZAP_SCAN_QUEUE_RETRY_SPIDER_MULTIPLIER`

The retired expert retry/requeue tools are not exposed to agents. Internal retry policy
still respects the configured per-type attempt budget. It cannot retry an
unresolved launch or bypass its ownership hold.

## Operator Notes

- inspect `retryAt` along with claim owner and claim expiry when debugging HA behavior
- if failover is too aggressive during long startup or polling windows, raise `ZAP_SCAN_QUEUE_CLAIM_LEASE_MS`
- if transient failures cause queue churn, increase backoff or lower max attempts
