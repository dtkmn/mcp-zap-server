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

## Retryable Errors

Auto-retry is applied for transient queue execution failures:

- scan startup failure

Runtime polling failures preserve the running job and its engine scan ID for the next status refresh; they do not restart the scan or consume startup attempts.

Non-retryable paths:

- invalid input rejected before enqueue
- manual cancellation
- inconsistent internal running state

## Backoff Formula

For attempt `n`:

`delayMs = min(maxBackoffMs, round(initialBackoffMs * multiplier^(n - 1)))`

## Runtime Overrides

- `ZAP_SCAN_QUEUE_ENGINE_BUSY_MAX_WAIT_MS`
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
