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

## Waiting for a Busy Engine

An explicit engine-busy rejection keeps the job `QUEUED (waiting for engine)` without consuming a startup attempt. The queue reuses the scan family's backoff settings, counting busy responses separately. Status and job listings show the waiting reason and next retry time. Waiting jobs can be cancelled normally.

`ZAP_SCAN_QUEUE_ENGINE_BUSY_MAX_WAIT_MS` sets the maximum wait from the first busy response. The default is `180000` (three minutes); `0` disables waiting and fails immediately. Negative values are rejected. This limit applies to engine-busy waiting, not time spent queued before the first launch attempt. It is separate from the startup retry budget.

The waiting timestamp and backoff count are persisted, so restarts and worker changes do not renew the budget. On the next dispatch cycle after expiry, the job becomes `FAILED` with a timeout reason, even if capacity is full or its next retry is later. A start already in flight retains the existing dispatch timeout and claim rules. Manual retry starts a new waiting window and still respects the remaining startup attempt budget.

Engine integrations identify explicit busy responses; the queue does not guess from error-message text. ZAP's `scan_in_progress` launch response is currently recognized. Other launch errors continue through normal startup retries. This handles the period when AJAX Spider reports stopped but ZAP is still cleaning up; it does not repair ZAP's immediate-stop error.

Configure this environment variable alongside the existing retry controls in `.env`/Docker Compose or your deployment's environment settings. PostgreSQL deployments require migration V7, which adds the two busy-wait fields; standard Flyway startup and the Helm migration bundle include it.

## AJAX Cancellation During Startup

Cancelling an AJAX queue job records a durable cancellation request. If ZAP rejects the stop while its crawler initializes, the job shows `cancellation pending` and the queue retries using the spider family's backoff. Stop failures do not consume startup attempts, and a generic ZAP internal error is not interpreted as proof of initialization.

`ZAP_SCAN_QUEUE_AJAX_CANCEL_MAX_WAIT_MS` sets the stop retry window, default `30000` (30 seconds), and must be positive. The original deadline and retry schedule survive restarts and worker changes. Repeating cancellation while it is pending preserves that deadline. Once the window expires, automatic stop retries end and status reports that cancellation is unconfirmed and the crawl may still be running. An explicit new cancellation request opens a new window.

The job retains its AJAX slot until stop is accepted or later status observation confirms the crawler is stopped. Cancelling a job whose start is already in flight also retains ownership. If startup never returns a scan ID, or persisted jobs disagree about who owns the crawler, the queue does not issue a global stop and cancellation remains unconfirmed. Ordinary queued jobs that have not started are cancelled immediately without calling ZAP. Active scans and the traditional spider keep their existing cancellation behavior.

Managed AJAX starts and cancellation attempts share a lifecycle lock, including across PostgreSQL workers, because ZAP's stop API is global. Direct AJAX starts are rejected while a queued crawl owns the engine. Use the same job store for all replicas controlling one ZAP instance, and do not start unrelated AJAX crawls through ZAP's API/UI while this server owns it: synthetic AJAX IDs cannot fence those external callers.

The deadline bounds retry scheduling. An individual in-flight ZAP API call still uses the existing Java client's transport behavior; its lifecycle lock is retained until the call returns, so an abandoned stop call cannot later stop a newer managed crawl. This is not a new socket timeout. PostgreSQL deployments require migration V8, included in the application and Helm migration bundles.

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
- `ZAP_SCAN_QUEUE_AJAX_CANCEL_MAX_WAIT_MS`
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
