---
title: "Queue Coordinator and Worker Claims"
editUrl: false
description: "Understand claim-based HA queue execution and the optional coordinator leadership signals."
---
## Objective

Guarantee safe multi-replica queue execution by using durable worker claims for dispatch ownership while keeping the coordinator backend available for node identity, optional maintenance leadership, and observability.

## Default Behavior

- `single-node` backend: single-process runtime with a stable local worker identity
- `postgres-lock` backend: replicas still compete for a Postgres advisory lock, but normal queue dispatch no longer depends on that lock

That last point matters. If you still think only one leader can dispatch scans, your mental model is behind the code.

## Configuration

```yaml
zap:
  scan:
    queue:
      claim-lease-ms: 15000
      coordinator:
        backend: single-node
        node-id: mcp-zap-node-1
        postgres:
          url: jdbc:postgresql://postgres:5432/mcp_zap
          username: mcp
          password: secret
          advisory-lock-key: 861001
          heartbeat-interval-ms: 5000
          fail-fast: false
```

Environment variables:

- `ZAP_SCAN_QUEUE_CLAIM_LEASE_MS`
- `ZAP_SCAN_QUEUE_COORDINATOR_BACKEND`
- `ZAP_SCAN_QUEUE_COORDINATOR_NODE_ID`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_URL`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_USERNAME`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_PASSWORD`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_ADVISORY_LOCK_KEY`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_HEARTBEAT_INTERVAL_MS`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_FAIL_FAST`

## Runtime Semantics

- any replica can admit queued jobs into shared durable `scan_jobs` state
- any replica can serve durable read paths and job mutations
- any replica can claim queued work, start scans, and poll running progress once it owns the durable claim
- running scans keep a renewable claim lease
- if a worker disappears and the lease expires, another replica can recover polling ownership from the stored ZAP scan ID
- queue workers only apply start/poll results if they still own the same fenced claim
- lease renewal keeps the same fence; reclaim after expiry creates a new fence
- if a late start result arrives after ownership moved, the stale result is rejected and the stray scan is stopped instead of being adopted twice
- queued active/spider capacity is checked under the Postgres queue mutation boundary so replicas cannot over-claim the same global capacity slot
- queue admission supports an optional client-generated `idempotencyKey`; a safe retry returns the existing durable job rather than creating a duplicate
- queue status responses surface claim owner and claim expiry

## Observability

Metrics:

- `mcp.zap.queue.leadership.is_leader`
- `mcp.zap.queue.leadership.transitions`
- `mcp.zap.queue.leadership.failures`
- `mcp.zap.queue.claim.events`

Queue claim events include:

- `queued_claimed`
- `running_recovered`
- `expired_claim_recovered`
- `renewed`
- `conflict`
- `late_result_cleanup`

## Failure Test Scenario

1. start 2 replicas with shared queue state
2. submit queued jobs and confirm no duplicate scan starts
3. terminate the worker currently polling a running job
4. wait for the claim lease to expire
5. verify another replica recovers polling without starting a duplicate scan

If you use `postgres-lock`, also verify the coordinator leadership metrics still behave as expected.

## Troubleshooting

- MCP replicas fail with missing-schema errors:
  - run Flyway migrations before scaling replicas
  - verify the expected shared tables exist (`scan_jobs`, `jwt_token_revocation`, and `scan_history_entries` when the ledger is Postgres-backed)
- Claims are not recovering:
  - verify worker clocks are reasonably in sync
  - confirm Flyway/schema readiness completed and `claim_owner_id`, `claim_fence_id`, `claim_heartbeat_at`, and `claim_expires_at` columns are present in `scan_jobs`
  - increase `ZAP_SCAN_QUEUE_CLAIM_LEASE_MS` if startup or polling calls can legitimately run longer
- Duplicate queued jobs after client retry:
  - provide a stable `idempotencyKey` when submitting queued scans
  - reuse the same key only for the same logical request payload
