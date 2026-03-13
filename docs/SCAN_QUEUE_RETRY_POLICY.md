# Scan Queue Retry and Backoff Policy

## Scope

This policy applies to queue-managed scan job families:

- `ACTIVE_SCAN`, `ACTIVE_SCAN_AS_USER`
- `SPIDER_SCAN`, `SPIDER_SCAN_AS_USER`

## Default Policy (Queue v1)

| Scan family | Max attempts | Initial backoff | Multiplier | Max backoff |
| --- | --- | --- | --- | --- |
| Active family | 3 | 2000 ms | 2.0 | 30000 ms |
| Spider family | 2 | 1000 ms | 2.0 | 10000 ms |

`maxAttempts` includes the first execution attempt.

## Retryable Error Classification

Auto-retry is applied for transient queue execution failures:

- Scan startup failure (`start*Scan*` call throws)
- Runtime polling failure (`get*ProgressPercent` call throws)

Non-retryable paths:

- Invalid input rejected before enqueue (for example URL validation failure)
- Manual cancellation (`CANCELLED`)
- Internal inconsistent running state (missing ZAP scan id while `RUNNING`)

## Backoff Formula

For attempt `n` (where `n` is completed attempts and starts at `1`):

`delayMs = min(maxBackoffMs, round(initialBackoffMs * multiplier^(n - 1)))`

If `initialBackoffMs` or `maxBackoffMs` is `0`, delay is treated as immediate (`0 ms`).

## Operator Overrides

Policy is configurable at runtime via environment variables:

- `ZAP_SCAN_QUEUE_RETRY_ACTIVE_MAX_ATTEMPTS`
- `ZAP_SCAN_QUEUE_RETRY_ACTIVE_INITIAL_BACKOFF_MS`
- `ZAP_SCAN_QUEUE_RETRY_ACTIVE_MAX_BACKOFF_MS`
- `ZAP_SCAN_QUEUE_RETRY_ACTIVE_MULTIPLIER`
- `ZAP_SCAN_QUEUE_RETRY_SPIDER_MAX_ATTEMPTS`
- `ZAP_SCAN_QUEUE_RETRY_SPIDER_INITIAL_BACKOFF_MS`
- `ZAP_SCAN_QUEUE_RETRY_SPIDER_MAX_BACKOFF_MS`
- `ZAP_SCAN_QUEUE_RETRY_SPIDER_MULTIPLIER`

Manual retry (`zap_scan_job_retry`) requeues immediately (`retryAt = now`) but still enforces the configured per-type attempt budget.

## Runbook Impact

- Use `zap_scan_job_status` or `zap_scan_job_list` to inspect `Retry Not Before` / `retryAt`.
- If transient failures cause queue churn, increase backoff and/or reduce max attempts.
- For strict fail-fast operations, set `*_MAX_ATTEMPTS=1`.
- Active scans are intentionally more persistent than spider scans by default; tune per environment based on target stability and API rate limits.
