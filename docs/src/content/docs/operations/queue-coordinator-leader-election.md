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

Dispatch ownership comes from durable worker claims. Coordinator leadership
does not restrict normal dispatch to one replica.

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
- if a worker disappears and its lease expires, outstanding launch receipts are held as `START_OUTCOME_UNKNOWN`; another replica does not automatically adopt the stored ZAP scan ID
- queue workers only apply poll results if they still own the same fenced claim
- lease renewal keeps the same fence; reclaim after expiry creates a new fence
- a late scan ID is accepted only for its exact outstanding launch attempt and resolution version; rejecting a stale result does not prove that its scan stopped
- queued active/spider capacity is checked under the Postgres queue mutation boundary so replicas cannot over-claim the same global capacity slot
- queue admission supports an optional client-generated `idempotencyKey`; a safe retry returns the existing durable job rather than creating a duplicate
- queue status responses surface claim owner and claim expiry

Held jobs retain their capacity reservations, and ZAP may still be running the
scan. The current MCP tools cannot manually adopt the scan or clear its hold.
See [Restart and Ownership](../scan-queue-retry-policy/#restart-and-ownership)
for the recovery limitations.

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
3. confirm a running job has a recorded native scan ID, then terminate its worker
4. wait for the claim lease to expire and another worker to process the queue
5. verify the outstanding launch is held as `START_OUTCOME_UNKNOWN`, with its capacity reserved and no duplicate scan start
6. confirm another replica does not automatically resume polling that native scan ID

If you use `postgres-lock`, also verify the coordinator leadership metrics still behave as expected.

## Troubleshooting

- MCP replicas fail with missing-schema errors:
  - run Flyway migrations before scaling replicas
  - verify the expected shared tables exist (`scan_jobs`, `jwt_token_revocation`, and `scan_history_entries` when the ledger is Postgres-backed)
- A job remains held after worker loss:
  - this is expected for an outstanding launch whose ownership is unconfirmed; a saved scan ID alone does not permit adoption
  - preserve the job database and journal; do not delete recovery state or submit a replacement scan to bypass the hold
- Claims expire unexpectedly:
  - verify worker clocks are reasonably in sync
  - confirm Flyway/schema readiness completed and `claim_owner_id`, `claim_fence_id`, `claim_heartbeat_at`, and `claim_expires_at` columns are present in `scan_jobs`
  - increase `ZAP_SCAN_QUEUE_CLAIM_LEASE_MS` if startup or polling calls can legitimately run longer
- Duplicate queued jobs after client retry:
  - provide a stable `idempotencyKey` when submitting queued scans
  - reuse the same key only for the same logical request payload
