---
title: "Scan Queue Retry and Backoff Policy"
editUrl: false
description: "Default retry budgets and backoff for queued scan families."
---
## Scope

This policy applies to queue-managed scan job families:

- `ACTIVE_SCAN`, `ACTIVE_SCAN_AS_USER`
- `SPIDER_SCAN`, `SPIDER_SCAN_AS_USER`, `AJAX_SPIDER`

## Default Policy

| Scan family | Max attempts | Initial backoff | Multiplier | Max backoff |
| --- | --- | --- | --- | --- |
| Active family | 3 | 2000 ms | 2.0 | 30000 ms |
| Spider family, including queued AJAX Spider | 2 | 1000 ms | 2.0 | 10000 ms |

`maxAttempts` includes the first execution attempt.

## ZAP API Connection and Read Timeouts

All calls from this server to ZAP use configurable HTTP timeouts:

| Environment variable | Application property | Default |
| --- | --- | --- |
| `ZAP_API_CONNECT_TIMEOUT_MS` | `zap.server.connect-timeout-ms` | `5000` (5 seconds) |
| `ZAP_API_READ_TIMEOUT_MS` | `zap.server.read-timeout-ms` | `10000` (10 seconds) |

Both values must be positive; startup rejects zero or negative values. The connection timeout limits connection establishment, and the read timeout limits inactivity while receiving a response. They are per-request limits, not a total scan duration or a strict overall call deadline. Configure them in `.env`/Docker Compose, deployment environment variables, or Helm's `mcp.zapClient.connectTimeoutMs` and `mcp.zapClient.readTimeoutMs` values.

A timeout does not prove that ZAP rejected an action: ZAP may still process it or may have completed it before its response was lost. Existing queue retry and cancellation policies still apply; cancellation stays unconfirmed until a stop is accepted or later status observation confirms it.

`ZAP_CONNECTION_TIMEOUT` controls ZAP's connections to scan targets in seconds and is applied at startup through `zap.initialization.connectionTimeoutInSecs`. An existing `ZAP_INIT_CONNECTION_TIMEOUT` value takes precedence; when neither variable is set, the effective default remains 300 seconds. The example environment and Helm value `mcp.scan.limits.connectionTimeoutInSecs` specify 60 seconds. These values were previously ignored and now take effect. The unused application property `zap.scan.limits.connectionTimeoutInSecs` has been removed; deployments that set that property directly should use `zap.initialization.connectionTimeoutInSecs`.

## Waiting for a Busy Engine

An explicit engine-busy rejection keeps the job `QUEUED (waiting for engine)` without consuming a startup attempt. The queue reuses the scan family's backoff settings, counting busy responses separately. Status and job listings show the waiting reason and next retry time. Waiting jobs can be cancelled normally.

`ZAP_SCAN_QUEUE_ENGINE_BUSY_MAX_WAIT_MS` sets the maximum wait from the first busy response. The default is `180000` (three minutes); `0` disables waiting and fails immediately. Negative values are rejected. This limit applies to engine-busy waiting, not time spent queued before the first launch attempt. It is separate from the startup retry budget.

The waiting timestamp and backoff count are persisted, so restarts and worker changes do not renew the budget. On the next dispatch cycle after expiry, the job becomes `FAILED` with a timeout reason, even if capacity is full or its next retry is later. A start already in flight retains the existing dispatch timeout and claim rules. Manual retry starts a new waiting window and still respects the remaining startup attempt budget.

Engine integrations identify explicit busy responses; the queue does not guess from error-message text. ZAP's `scan_in_progress` launch response is currently recognized. Other launch errors continue through normal startup retries. This handles the period when AJAX Spider reports stopped but ZAP is still cleaning up; it does not repair ZAP's immediate-stop error.

Configure this environment variable alongside the existing retry controls in `.env`/Docker Compose or your deployment's environment settings. PostgreSQL deployments require migration V7, which adds the two busy-wait fields; standard Flyway startup and the Helm migration bundle include it.

## Shared Cancellation and Cleanup Retry Window

`ZAP_SCAN_QUEUE_CANCEL_MAX_WAIT_MS` (`zap.scan.queue.cancel-max-wait-ms`) sets one stop retry window for cancellation of running queue jobs, AJAX cancellation during startup, and abandoned active/traditional spider cleanup. It defaults to `30000` (30 seconds) and must be positive. All paths use the scan family's existing backoff without consuming startup attempts.

The former `ZAP_SCAN_QUEUE_AJAX_CANCEL_MAX_WAIT_MS` environment variable and `zap.scan.queue.ajax-cancel-max-wait-ms` property remain fallback aliases for this shared setting. The new setting takes precedence. Configure it in `.env`/Docker Compose, deployment environment variables, or Helm's `mcp.env` list.

The original deadline and retry schedule survive worker changes and PostgreSQL-backed restarts. Repeating cancellation while it is pending preserves the deadline. Once the window expires, automatic stop retries end and status reports that cancellation is unconfirmed and the scan may still be running. An explicit new cancellation request opens a new window. The job retains capacity until a stop succeeds or status observation confirms completion.

Cancelling a running active scan or traditional spider, including authenticated scans, saves cancellation on the existing job before attempting to stop its ZAP scan ID. A failed stop remains pending and retries within this window; it does not create a cleanup record. Other scans can continue using the remaining capacity.

## AJAX Cancellation During Startup

Cancelling an AJAX queue job records a durable cancellation request. If ZAP rejects the stop while its crawler initializes, the job shows `cancellation pending` and the queue retries using the spider family's backoff. Stop failures do not consume startup attempts, and a generic ZAP internal error is not interpreted as proof of initialization.

The job retains its AJAX slot until stop is accepted or later status observation confirms the crawler is stopped. Cancelling a job whose start is already in flight also retains ownership. If startup never returns a scan ID, or persisted jobs disagree about who owns the crawler, the queue does not issue a global stop and cancellation remains unconfirmed. Ordinary queued jobs that have not started are cancelled immediately without calling ZAP.

Managed AJAX starts and cancellation attempts share a lifecycle lock, including across PostgreSQL workers, because ZAP's stop API is global. Direct AJAX starts are rejected while a queued crawl owns the engine. Use the same job store for all replicas controlling one ZAP instance, and do not start unrelated AJAX crawls through ZAP's API/UI while this server owns it: synthetic AJAX IDs cannot fence those external callers.

If an AJAX start succeeds after its queue dispatch times out, the queue records the accepted crawl before releasing the lifecycle lock and cleans it up through the same cancellation retry window. A failed cleanup keeps the crawl tracked and reserves its slot. Delayed cleanup callbacks must still match the job's current crawl and cannot stop a newer crawl after the original job has finished or been cancelled.

The cancellation deadline bounds retry scheduling, separately from the connection and read timeouts above. An in-flight stop can outlast the cancellation retry window; its lifecycle lock is retained until the call returns, and a timed-out stop leaves cancellation unconfirmed. PostgreSQL deployments require migration V8, included in the application and Helm migration bundles.

## Late Active Scan and Traditional Spider Cleanup

When an active scan or traditional spider returns its ZAP scan ID after dispatch timed out or its start result can no longer be adopted, cleanup is persisted before attempting a stop. This includes authenticated scans. A separate cleanup record links to the original job and retains the abandoned scan ID, so a newer attempt's scan ID and claim are preserved. Job details and listings identify the source job with `Cleanup for Job ID` / `cleanupForJob`.

Cleanup uses the shared cancellation window above; duplicate callbacks do not renew it. In-memory storage remains process-local.

Each unresolved cleanup retains one slot in its scan family. Further launches or manual retries of the source job wait until its cleanup resolves; other jobs can use remaining capacity. An attempt already dispatched before the late response is discovered can still finish and is tracked independently. Cleanup always targets the original ZAP scan ID and does not take the AJAX lifecycle lock. Cleanup records cannot be retried or replayed as new scans.

After the retry deadline, the cleanup remains visible as cancellation unconfirmed and continues to reserve capacity. Status polling can confirm the scan has finished. Use `zap_scan_job_cancel` with the cleanup record's job ID to explicitly open another stop retry window. If recording cleanup fails, the server attempts an immediate stop of that exact scan ID; recovery cannot be guaranteed when both storage and stopping fail. This path requires a returned scan ID and cannot recover an ID that ZAP never delivered.

## Retryable Errors

Auto-retry is applied for transient queue execution failures:

- scan startup failure

Runtime polling failures preserve the running job and its engine scan ID for the next status refresh; they do not restart the scan or consume startup attempts.

Non-retryable paths:

- invalid input rejected before enqueue
- cancelled jobs are not automatically restarted
- inconsistent internal running state

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

Manual retry through `zap_scan_job_retry` requeues immediately but still respects the configured per-type attempt budget.

## Operator Notes

- inspect `retryAt` along with claim owner and claim expiry when debugging HA behavior
- if failover is too aggressive during long startup or polling windows, raise `ZAP_SCAN_QUEUE_CLAIM_LEASE_MS`
- if transient failures cause queue churn, increase backoff or lower max attempts
